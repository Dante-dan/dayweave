package app.dayweave.probe

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 每次探测会话一个目录：metrics.csv（每 10 秒一行）、events.txt（路由与异常事件）、
 * samples/（用于人工试听收音效果的短 WAV）。
 * 位于 /sdcard/Android/data/app.dayweave.probe/files/sessions/，可直接 adb pull。
 */
class SessionFiles(context: Context) {
    val dir: File = File(
        context.getExternalFilesDir(null),
        "sessions/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")),
    ).apply { mkdirs() }

    private val metrics = File(dir, "metrics.csv").bufferedWriter()
    private val events = File(dir, "events.txt").bufferedWriter()

    init {
        metrics.write(
            "time,elapsed_s,source,routed_input,comm_device,audio_mode,mean_dbfs,max_dbfs," +
                "zero_frame_ratio,silenced_ratio,other_recorders,samples_read,samples_expected,read_errors," +
                "battery_pct,current_ua,screen_interactive\n",
        )
        metrics.flush()
    }

    @Synchronized
    fun metric(row: List<Any?>) {
        metrics.write(row.joinToString(",") { it?.toString()?.replace(',', ';') ?: "" })
        metrics.newLine()
        metrics.flush()
    }

    @Synchronized
    fun event(message: String) {
        events.write("${LocalDateTime.now()} $message")
        events.newLine()
        events.flush()
    }

    fun newSample(label: String): WavWriter {
        val samples = File(dir, "samples").apply { mkdirs() }
        val name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + "-$label.wav"
        return WavWriter(File(samples, name), SAMPLE_RATE)
    }

    @Synchronized
    fun close() {
        metrics.close()
        events.close()
    }
}

/** 16-bit 单声道 PCM WAV，关闭时回填长度。 */
class WavWriter(val file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var dataBytes = 0L

    fun write(pcm: ShortArray, count: Int) {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            bytes[i * 2] = (pcm[i].toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = (pcm[i].toInt() shr 8 and 0xff).toByte()
        }
        raf.write(bytes)
        dataBytes += bytes.size
    }

    fun close() {
        raf.seek(0)
        raf.write(header())
        raf.close()
    }

    private fun header(): ByteArray {
        val byteRate = sampleRate * 2
        val buf = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buf.putInt(sampleRate).putInt(byteRate).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataBytes.toInt())
        return buf.array()
    }
}
