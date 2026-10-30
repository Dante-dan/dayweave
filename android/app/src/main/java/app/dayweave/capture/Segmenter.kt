package app.dayweave.capture

import app.dayweave.core.SAMPLE_RATE
import app.dayweave.core.Segment
import app.dayweave.core.SourceKind
import app.dayweave.core.WavWriter
import java.io.File
import java.util.UUID
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

const val FRAME_SAMPLES = SAMPLE_RATE / 10 // 100 ms
private const val FRAME_MS = 100L
private const val PRE_ROLL_FRAMES = 10 // 1 s，避免漏掉讲话开头
private const val START_FRAMES = 3 // 连续 300 ms 人声才开段
private const val END_SILENCE_MS = 2_500L
private const val MAX_SEGMENT_MS = 15 * 60_000L
private const val MIN_SPEECH_MS = 800L

/**
 * 轻量人声检测与分段：按停顿结束片段，持续讲话时最长 15 分钟切一段。
 * 噪声底自适应：遇到更安静的帧快速下调，持续较响时缓慢上调，避免把稳定的环境噪声当成人声。
 */
class Segmenter(
    private val source: SourceKind,
    private val dir: File,
    private val device: () -> String,
    private val onSegment: (Segment, File) -> Unit,
) {
    private var noiseFloor = -60.0
    private val preRoll = ArrayDeque<ShortArray>()
    private var speechRun = 0

    private var writer: WavWriter? = null
    private var segmentId = ""
    private var startedAt = 0L
    private var lastSpeechAt = 0L
    private var speechMs = 0L

    /** 最近一帧电平，供界面显示。 */
    @Volatile var levelDb = -90.0
        private set

    val active get() = writer != null

    fun accept(frame: ShortArray, n: Int, now: Long) {
        val db = dbfs(frame, n)
        levelDb = db
        noiseFloor = if (db < noiseFloor) noiseFloor * 0.7 + db * 0.3 else (noiseFloor + 0.05).coerceAtMost(-30.0)
        val speech = db > max(noiseFloor + 10, -58.0)
        val copy = frame.copyOf(n)

        val w = writer
        if (w == null) {
            preRoll.addLast(copy)
            if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            speechRun = if (speech) speechRun + 1 else 0
            if (speechRun >= START_FRAMES) open(now - preRoll.size * FRAME_MS, now, withPreRoll = true)
            return
        }

        w.write(copy)
        if (speech) {
            lastSpeechAt = now
            speechMs += FRAME_MS
        }
        when {
            now - lastSpeechAt >= END_SILENCE_MS -> close()
            now - startedAt >= MAX_SEGMENT_MS -> {
                close()
                if (speech) open(now, now, withPreRoll = false)
            }
        }
    }

    /** 停止采集时收尾当前片段。 */
    fun flush() {
        if (writer != null) close()
        preRoll.clear()
        speechRun = 0
    }

    private fun open(at: Long, now: Long, withPreRoll: Boolean) {
        segmentId = UUID.randomUUID().toString()
        val w = WavWriter(File(dir, "$segmentId.wav"))
        if (withPreRoll) preRoll.forEach { w.write(it) }
        preRoll.clear()
        writer = w
        startedAt = at
        lastSpeechAt = now
        speechMs = if (withPreRoll) START_FRAMES * FRAME_MS else 0
    }

    private fun close() {
        val w = writer ?: return
        writer = null
        w.close()
        speechRun = 0
        if (speechMs < MIN_SPEECH_MS) {
            w.file.delete()
            return
        }
        val endedAt = startedAt + w.samples * 1000 / SAMPLE_RATE
        onSegment(Segment(id = segmentId, startedAt = startedAt, endedAt = endedAt, source = source, device = device()), w.file)
    }
}

fun dbfs(frame: ShortArray, n: Int): Double {
    if (n <= 0) return -90.0
    var sum = 0.0
    for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
    val rms = sqrt(sum / n)
    return if (rms <= 0) -90.0 else 20 * log10(rms / Short.MAX_VALUE)
}
