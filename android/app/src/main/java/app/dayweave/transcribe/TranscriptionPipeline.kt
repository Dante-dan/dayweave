package app.dayweave.transcribe

import android.util.Log
import app.dayweave.core.ArchiveStore
import app.dayweave.core.SAMPLE_RATE
import app.dayweave.core.Segment
import app.dayweave.core.SegmentStatus
import app.dayweave.core.Turn
import app.dayweave.core.UNKNOWN_ID
import app.dayweave.core.readPcm
import app.dayweave.voice.VoiceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

private const val TAG = "Dayweave"
private const val CHUNK_MAX = SAMPLE_RATE * 55 // 同步识别单次上限 60 秒，留余量
private const val CHUNK_SEARCH = SAMPLE_RATE * 15 // 在最后 15 秒里找最安静处切开
private const val FRAME = SAMPLE_RATE / 10
private const val TURN_GAP_MS = 1_200L

/**
 * 第一步“忠实记录”的后半段：临时音频 → Chirp 3 转写 → 声纹归属 → 文字档案 → 删除音频。
 * 片段按顺序处理；网络失败保持待转写并稍后重试，文字可靠保存后才删除音频。
 */
class TranscriptionPipeline(
    private val archive: ArchiveStore,
    private val chirp: ChirpClient,
    private val voice: VoiceId,
    private val audioDir: File,
    private val scope: CoroutineScope,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            archive.segments.value
                .filter { it.status == SegmentStatus.PENDING || it.status == SegmentStatus.TRANSCRIBING }
                .forEach { queue.send(it.id) }
            for (id in queue) process(id)
        }
    }

    private fun audioFile(id: String) = File(audioDir, "$id.wav")

    fun submit(segment: Segment, file: File) {
        if (file != audioFile(segment.id)) file.renameTo(audioFile(segment.id))
        archive.save(segment)
        queue.trySend(segment.id)
    }

    fun retry(id: String) {
        val segment = archive.get(id) ?: return
        archive.save(segment.copy(status = SegmentStatus.PENDING, error = null))
        queue.trySend(id)
    }

    private suspend fun process(id: String) {
        val segment = archive.get(id) ?: return
        val file = audioFile(id)
        if (!file.exists()) {
            archive.save(segment.copy(status = SegmentStatus.FAILED, error = "临时音频已不存在，无法转写"))
            return
        }
        if (!chirp.available) {
            archive.save(segment.copy(status = SegmentStatus.FAILED, error = "还没有配置转写凭证"))
            return
        }
        archive.save(segment.copy(status = SegmentStatus.TRANSCRIBING, error = null))
        try {
            val pcm = readPcm(file)
            val turns = mutableListOf<Turn>()
            val languages = mutableSetOf<String>()
            for ((from, to) in chunks(pcm)) {
                val recognition = withRetry { chirp.recognize(pcm, from, to) }
                languages += recognition.languages
                turns += attribute(pcm, from, recognition.words)
            }
            if (turns.isEmpty()) {
                // 没有可识别的语音（例如环境噪声），不建立档案。
                archive.delete(id)
            } else {
                archive.save(segment.copy(status = SegmentStatus.DONE, turns = mergeTurns(turns), languages = languages.toList()))
            }
            file.delete()
        } catch (e: IOException) {
            Log.w(TAG, "transcribe $id offline, retry later", e)
            archive.save(segment.copy(status = SegmentStatus.PENDING, error = "网络不可用，稍后重试"))
            scope.launch {
                delay(60_000)
                queue.send(id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "transcribe $id failed", e)
            archive.save(segment.copy(status = SegmentStatus.FAILED, error = e.message ?: e.javaClass.simpleName))
        }
    }

    private suspend fun <T> withRetry(block: () -> T): T {
        var wait = 2_000L
        repeat(2) {
            try {
                return block()
            } catch (e: IOException) {
                delay(wait)
                wait *= 3
            } catch (e: HttpException) {
                if (e.code < 500 && e.code != 429) throw e
                delay(wait)
                wait *= 3
            }
        }
        return block()
    }

    /** 切成不超过 55 秒的块，切点放在最后 15 秒里最安静的 100 ms。 */
    private fun chunks(pcm: ShortArray): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (pcm.size - start > CHUNK_MAX) {
            var cut = start + CHUNK_MAX
            var quietest = Double.MAX_VALUE
            var pos = start + CHUNK_MAX - CHUNK_SEARCH
            while (pos + FRAME <= start + CHUNK_MAX) {
                var energy = 0.0
                for (i in pos until pos + FRAME) energy += pcm[i].toDouble() * pcm[i]
                if (energy < quietest) {
                    quietest = energy
                    cut = pos + FRAME / 2
                }
                pos += FRAME
            }
            result += start to cut
            start = cut
        }
        if (pcm.size - start > SAMPLE_RATE / 2) result += start to pcm.size
        return result
    }

    /**
     * 把同一块里同一个说话人标签的音频拼起来提取声纹，判断是主人还是匿名说话人。
     * Chirp 的标签只在单次请求内有效，声纹让不同块、不同片段之间的同一个人保持一致。
     */
    private fun attribute(pcm: ShortArray, chunkStart: Int, words: List<Word>): List<Turn> {
        if (words.isEmpty()) return emptyList()
        val baseMs = chunkStart * 1000L / SAMPLE_RATE
        val identities = words.groupBy { it.label }.mapValues { (_, labelWords) ->
            val audio = labelWords.flatMap { w ->
                val from = chunkStart + (w.startMs * SAMPLE_RATE / 1000).toInt()
                val to = (chunkStart + (w.endMs * SAMPLE_RATE / 1000).toInt()).coerceAtMost(pcm.size)
                if (to > from) pcm.copyOfRange(from, to).asIterable() else emptyList()
            }.toShortArray()
            voice.embed(audio)?.let(voice::resolve) ?: (UNKNOWN_ID to null)
        }

        val turns = mutableListOf<Turn>()
        var current: Turn? = null
        for (w in words) {
            val (speaker, score) = identities.getValue(w.label)
            val start = baseMs + w.startMs
            val end = baseMs + w.endMs
            val c = current
            current = if (c != null && c.speakerId == speaker && start - c.endMs <= TURN_GAP_MS) {
                c.copy(endMs = end, text = joinText(c.text, w.text))
            } else {
                c?.let(turns::add)
                Turn(speaker, start, end, w.text.trim(), score)
            }
        }
        current?.let(turns::add)
        return turns.filter { it.text.isNotBlank() }
    }

    private fun mergeTurns(turns: List<Turn>): List<Turn> = turns.fold(mutableListOf()) { acc, t ->
        val last = acc.lastOrNull()
        if (last != null && last.speakerId == t.speakerId && t.startMs - last.endMs <= TURN_GAP_MS) {
            acc[acc.lastIndex] = last.copy(endMs = t.endMs, text = joinText(last.text, t.text))
        } else {
            acc += t
        }
        acc
    }
}

/** 中文字之间不加空格，英文词之间加空格。 */
fun joinText(left: String, right: String): String {
    val r = right.trim()
    if (left.isEmpty()) return r
    if (r.isEmpty()) return left
    val a = left.last()
    val b = r.first()
    val latin = { c: Char -> c.code < 128 && (c.isLetterOrDigit() || c == '\'') }
    return if (latin(a) && latin(b) || (a in ",.!?;:" && latin(b))) "$left $r" else left + r
}
