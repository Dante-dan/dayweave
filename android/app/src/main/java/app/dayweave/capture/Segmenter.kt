package app.dayweave.capture

import app.dayweave.core.SAMPLE_RATE
import app.dayweave.core.Scene
import app.dayweave.core.Segment
import app.dayweave.core.SourceKind
import app.dayweave.core.WavWriter
import java.io.File
import java.util.UUID
import kotlin.math.log10
import kotlin.math.sqrt

const val FRAME_SAMPLES = SAMPLE_RATE / 10 // 100 ms
private const val FRAME_MS = 100L
private const val PRE_ROLL_FRAMES = 10 // 1 s，避免漏掉讲话开头
private const val START_FRAMES = 3 // 连续 300 ms 人声才开段
private const val END_SILENCE_MS = 2_500L
private const val MAX_SEGMENT_MS = 15 * 60_000L
private const val MIN_SPEECH_MS = 800L
/** 低于它肯定没人说话，跳过模型判断。 */
private const val SILENT_DB = -65.0

/**
 * 人声检测与分段：电平先粗筛，再由本地 VAD 模型确认是不是人声，所以环境音、音乐不会开段。
 * 按停顿结束片段，持续讲话时最长 15 分钟切一段。
 */
class Segmenter(
    private val source: SourceKind,
    private val dir: File,
    private val detector: SpeechDetector,
    private val device: () -> String,
    private val scene: () -> Scene?,
    private val onSegment: (Segment, File) -> Unit,
) {
    private val preRoll = ArrayDeque<ShortArray>()
    private var speechRun = 0

    private var writer: WavWriter? = null
    private var segmentId = ""
    private var startedAt = 0L
    private var lastSpeechAt = 0L
    private var speechMs = 0L
    private var openScene: Scene? = null

    /** 最近一帧电平，供界面显示。 */
    @Volatile var levelDb = -90.0
        private set

    /** 最近一帧是否判定为人声。 */
    @Volatile var speaking = false
        private set

    fun accept(frame: ShortArray, n: Int, now: Long) {
        val db = dbfs(frame, n)
        levelDb = db
        val voice = detector.isSpeech(frame, n)
        val speech = voice && db > SILENT_DB
        speaking = speech
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

    /** 停止或暂停采集时收尾当前片段。 */
    fun flush() {
        if (writer != null) close()
        preRoll.clear()
        speechRun = 0
        speaking = false
        levelDb = -90.0
        detector.reset()
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
        openScene = scene()
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
        onSegment(
            Segment(id = segmentId, startedAt = startedAt, endedAt = endedAt, source = source, device = device(), scene = openScene ?: scene()),
            w.file,
        )
    }
}

fun dbfs(frame: ShortArray, n: Int): Double {
    if (n <= 0) return -90.0
    var sum = 0.0
    for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
    val rms = sqrt(sum / n)
    return if (rms <= 0) -90.0 else 20 * log10(rms / Short.MAX_VALUE)
}
