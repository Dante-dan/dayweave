package app.dayweave.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import app.dayweave.capture.FRAME_SAMPLES
import app.dayweave.capture.dbfs
import app.dayweave.core.InputSelection
import app.dayweave.core.SAMPLE_RATE
import kotlin.concurrent.thread

/**
 * 声纹录入时的录音：用和日常采集相同的输入设备，让录入的声纹和实际采集的声音特征一致。
 */
class EnrollRecorder(private val context: Context, private val selection: InputSelection?) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val bluetooth = selection?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || selection?.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    @Volatile private var running = false
    private var worker: Thread? = null
    private val buffer = ArrayList<ShortArray>()

    /** 实时电平，用于录音按钮的波动效果。 */
    @Volatile var levelDb = -90.0
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        buffer.clear()
        running = true
        val device = route()
        worker = thread(name = "enroll") {
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(SAMPLE_RATE * 2)
                .build()
            rec.preferredDevice = device
            rec.startRecording()
            val frame = ShortArray(FRAME_SAMPLES)
            while (running) {
                val n = rec.read(frame, 0, frame.size)
                if (n > 0) {
                    levelDb = dbfs(frame, n)
                    synchronized(buffer) { buffer += frame.copyOf(n) }
                }
            }
            rec.stop()
            rec.release()
        }
    }

    /** 停止并返回录到的 PCM。 */
    fun stop(): ShortArray {
        running = false
        worker?.join(2_000)
        worker = null
        if (bluetooth) {
            audio.clearCommunicationDevice()
            audio.mode = AudioManager.MODE_NORMAL
        }
        levelDb = -90.0
        return synchronized(buffer) {
            val out = ShortArray(buffer.sumOf { it.size })
            var pos = 0
            buffer.forEach { it.copyInto(out, pos); pos += it.size }
            out
        }
    }

    private fun route(): AudioDeviceInfo? {
        val s = selection ?: return null
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val match = { d: AudioDeviceInfo -> d.type == s.type && (!bluetooth || d.productName.toString() == s.name) }
        if (bluetooth) {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.availableCommunicationDevices.firstOrNull(match)?.let(audio::setCommunicationDevice)
        }
        return inputs.firstOrNull(match)
    }
}
