package app.dayweave.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16_000
private const val HEADER_BYTES = 44

/** 16-bit 单声道 PCM WAV，边录边写，关闭时回填长度。 */
class WavWriter(val file: File) {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(HEADER_BYTES)) }
    var samples = 0L
        private set

    fun write(pcm: ShortArray, offset: Int = 0, count: Int = pcm.size) {
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in offset until offset + count) bytes.putShort(pcm[i])
        raf.write(bytes.array())
        samples += count
    }

    fun close() {
        val dataBytes = samples * 2
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }
}

/** 读回 PCM。不信任文件头里的长度（进程被杀时可能没回填），直接按文件大小读。 */
fun readPcm(file: File): ShortArray {
    val bytes = file.readBytes()
    val count = (bytes.size - HEADER_BYTES).coerceAtLeast(0) / 2
    val buf = ByteBuffer.wrap(bytes, HEADER_BYTES, count * 2).order(ByteOrder.LITTLE_ENDIAN)
    return ShortArray(count) { buf.short }
}
