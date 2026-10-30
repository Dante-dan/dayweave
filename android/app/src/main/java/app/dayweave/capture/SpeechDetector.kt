package app.dayweave.capture

import android.content.Context
import app.dayweave.core.SAMPLE_RATE
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** 送去转写前，人声区域前后各留一点余量，避免切掉字头字尾。 */
private const val PAD_SAMPLES = SAMPLE_RATE / 4
/** 间隔小于它的两段人声合成一段，保持句子连贯。 */
private const val MERGE_GAP_SAMPLES = SAMPLE_RATE * 3 / 5
private const val FEED_SAMPLES = SAMPLE_RATE / 10

/**
 * 本地人声检测（Silero VAD，约 600 KB，在手机上运行）。只把真正有人说话的部分交给云端：
 * 静音、空调和车流这类环境音、远处的背景音乐都不转写，也不计费。
 * 非线程安全：每条采集线程、转写流水线各用一个实例。
 */
class SpeechDetector(context: Context) {
    private val vad = Vad(
        context.assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "vad/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 30f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        ),
    )

    /** 实时判断：喂入一帧，返回此刻是否有人在说话。 */
    fun isSpeech(frame: ShortArray, n: Int): Boolean {
        vad.acceptWaveform(FloatArray(n) { frame[it] / 32768f })
        while (!vad.empty()) vad.pop()
        return vad.isSpeechDetected()
    }

    fun reset() = vad.reset()

    /** 整段音频里的人声区间（样本下标，左闭右开），已加余量并合并相邻区间。 */
    fun speechRegions(pcm: ShortArray): List<IntRange> {
        vad.reset()
        val raw = mutableListOf<IntRange>()
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                raw += s.start until s.start + s.samples.size
                vad.pop()
            }
        }
        var pos = 0
        while (pos < pcm.size) {
            val end = minOf(pos + FEED_SAMPLES, pcm.size)
            vad.acceptWaveform(FloatArray(end - pos) { pcm[pos + it] / 32768f })
            drain()
            pos = end
        }
        vad.flush()
        drain()
        vad.reset()

        val merged = mutableListOf<IntRange>()
        for (r in raw.sortedBy { it.first }) {
            val padded = (r.first - PAD_SAMPLES).coerceAtLeast(0) until (r.last + 1 + PAD_SAMPLES).coerceAtMost(pcm.size)
            val last = merged.lastOrNull()
            if (last != null && padded.first - (last.last + 1) <= MERGE_GAP_SAMPLES) {
                merged[merged.lastIndex] = last.first until maxOf(last.last + 1, padded.last + 1)
            } else {
                merged += padded
            }
        }
        return merged
    }

    fun release() = vad.release()
}

/**
 * 只保留人声的紧凑音频，以及把紧凑音频里的时间换回原始片段时间的映射。
 */
class SpeechOnly(val pcm: ShortArray, private val regions: List<IntRange>) {
    private val starts = regions.runningFold(0) { acc, r -> acc + (r.last + 1 - r.first) }

    val durationMs get() = pcm.size * 1000L / SAMPLE_RATE

    /** 紧凑音频中的毫秒 → 原始片段中的毫秒。 */
    fun toOriginalMs(ms: Long): Long {
        if (regions.isEmpty()) return ms
        val sample = (ms * SAMPLE_RATE / 1000).toInt()
        val i = (starts.indexOfLast { it <= sample }).coerceIn(0, regions.lastIndex)
        return (regions[i].first + (sample - starts[i])) * 1000L / SAMPLE_RATE
    }

    companion object {
        fun of(pcm: ShortArray, regions: List<IntRange>): SpeechOnly {
            val out = ShortArray(regions.sumOf { it.last + 1 - it.first })
            var pos = 0
            for (r in regions) {
                pcm.copyInto(out, pos, r.first, r.last + 1)
                pos += r.last + 1 - r.first
            }
            return SpeechOnly(out, regions)
        }
    }
}
