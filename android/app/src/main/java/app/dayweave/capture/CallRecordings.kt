package app.dayweave.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.dayweave.core.SAMPLE_RATE
import app.dayweave.core.Scene
import app.dayweave.core.SceneKind
import app.dayweave.core.Segment
import app.dayweave.core.SourceKind
import app.dayweave.core.WavWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val TAG = "Dayweave"
/** 澎湃 OS / MIUI 自带通话录音的位置。 */
const val CALL_REC_DOCUMENT = "primary:MIUI/sound_recorder/call_rec"
/** 文件最后修改后静置这么久才导入，避免读到还在写的录音。 */
private const val SETTLE_MS = 20_000L
/** 长通话按 15 分钟切成多段，和日常采集的片段上限一致。 */
private const val PART_SAMPLES = SAMPLE_RATE * 60 * 15
/** “联系人(号码)_20261030153012.mp3” 或 “号码_20261030153012.mp3”。 */
private val NAME = Regex("""^(.*?)(?:\(([^)]*)\))?_(\d{14})\.\w+$""")

/**
 * 自动导入系统通话录音：用户只授权 call_rec 这一个文件夹（系统文件选择器，不需要存储权限）。
 * 只导入开启之后的新录音；原始录音文件保持不动，转写用的是解码后的临时副本。
 */
class CallRecordings(
    private val context: Context,
    private val submit: (Segment, File) -> Unit,
    private val audioDir: File,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("call_recordings", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val zone = ZoneId.systemDefault()

    private val _folder = MutableStateFlow(prefs.getString("tree", null)?.let(Uri::parse)?.takeIf(::stillGranted))
    /** 已授权的文件夹；未开启时为 null。 */
    val folder: StateFlow<Uri?> = _folder

    private val _imported = MutableStateFlow(prefs.getInt("count", 0))
    val imported: StateFlow<Int> = _imported

    private fun stillGranted(uri: Uri) = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /** 用户选好文件夹后调用。从现在起的新录音会被导入。 */
    fun enable(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString("tree", tree.toString()).putLong("since", System.currentTimeMillis()).apply()
        _folder.value = tree
    }

    fun disable() {
        _folder.value?.let { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().remove("tree").apply()
        _folder.value = null
    }

    /** 看看有没有新的通话录音。打开 App、记录中每隔几分钟都会调用，重复调用没有副作用。 */
    fun scan() {
        val tree = _folder.value ?: return
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val since = prefs.getLong("since", Long.MAX_VALUE)
                val done = prefs.getStringSet("done", emptySet())!!.toMutableSet()
                val now = System.currentTimeMillis()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val fresh = mutableListOf<Triple<String, String, Long>>()
                runCatching {
                    context.contentResolver.query(
                        children,
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                        null, null, null,
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0)
                            val modified = c.getLong(2)
                            if (modified >= since && now - modified >= SETTLE_MS && id !in done) fresh += Triple(id, c.getString(1), modified)
                        }
                    }
                }.onFailure { Log.w(TAG, "call_rec scan failed", it) }
                for ((id, name, modified) in fresh.sortedBy { it.third }) {
                    runCatching { import(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, modified) }
                        .onFailure { Log.w(TAG, "import $name failed", it) }
                    done += id
                    prefs.edit().putStringSet("done", done).putInt("count", done.size).apply()
                    _imported.value = done.size
                }
            }
        }
    }

    /** 仅调试构建：直接导入 App 私有目录里的一个音频文件，用来验证解码和切段。 */
    fun importForTest(file: File) {
        scope.launch(Dispatchers.IO) { mutex.withLock { import(Uri.fromFile(file), file.name, file.lastModified()) } }
    }

    private fun import(uri: Uri, name: String, modified: Long) {
        val match = NAME.find(name)
        val who = match?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val number = match?.groupValues?.get(2)?.ifBlank { null }
        val startedAt = match?.groupValues?.get(3)?.let {
            runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        } ?: modified
        val contact = who ?: number ?: "未知号码"
        val scene = Scene(SceneKind.CALL, app = "电话 · $contact", title = number?.takeIf { who != null })

        var part: WavWriter? = null
        var partStart = startedAt
        var partId = ""
        fun finish() {
            val w = part ?: return
            part = null
            w.close()
            if (w.samples < SAMPLE_RATE) {
                w.file.delete()
                return
            }
            val segment = Segment(
                id = partId, startedAt = partStart, endedAt = partStart + w.samples * 1000 / SAMPLE_RATE,
                source = SourceKind.CALL, device = "通话录音", scene = scene,
            )
            submit(segment, w.file)
            partStart = segment.endedAt
        }
        decodeTo16kMono(uri) { chunk ->
            var offset = 0
            while (offset < chunk.size) {
                val w = part ?: WavWriter(File(audioDir, UUID.randomUUID().toString().also { partId = it } + ".wav")).also { part = it }
                val n = minOf(chunk.size - offset, (PART_SAMPLES - w.samples).toInt())
                w.write(chunk, offset, n)
                offset += n
                if (w.samples >= PART_SAMPLES) finish()
            }
        }
        finish()
        Log.i(TAG, "imported call recording $name")
    }

    /** 用系统解码器把 mp3 / m4a / amr 解成 16 kHz 单声道 PCM，分块回调。 */
    private fun decodeTo16kMono(uri: Uri, onPcm: (ShortArray) -> Unit) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val resampler = Resampler()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = buf.remaining() / channels
                        val mono = ShortArray(frames) { f ->
                            var sum = 0
                            for (ch in 0 until channels) sum += buf.get(f * channels + ch)
                            (sum / channels).toShort()
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (mono.isNotEmpty()) onPcm(resampler.process(mono, rate))
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }
}

/** 线性插值重采样到 16 kHz，跨块保持相位连续。 */
private class Resampler {
    private var position = 0.0
    private var last: Short = 0

    fun process(input: ShortArray, rate: Int): ShortArray {
        if (rate == SAMPLE_RATE) return input
        val step = rate.toDouble() / SAMPLE_RATE
        val out = ArrayList<Short>((input.size / step).toInt() + 2)
        while (position < input.size) {
            val i = position.toInt()
            val frac = position - i
            val a = if (i == 0) last.toDouble() else input[i - 1].toDouble()
            val b = input[i].toDouble()
            out += (a + (b - a) * frac).toInt().toShort()
            position += step
        }
        position -= input.size
        last = input.last()
        return out.toShortArray()
    }
}
