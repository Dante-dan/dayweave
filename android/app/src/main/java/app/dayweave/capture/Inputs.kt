package app.dayweave.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import app.dayweave.core.InputSelection
import app.dayweave.core.SAMPLE_RATE
import kotlin.concurrent.thread
import kotlin.math.max

private const val TAG = "Dayweave"
private val BT_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
private const val STARTUP_TIMEOUT_MS = 10_000L
private const val STARTUP_REBUILD_MS = 3_000L
private const val REROUTE_INTERVAL_MS = 30_000L

data class InputOption(val selection: InputSelection, val isBluetooth: Boolean)

/** 可选的麦克风：每台在线的蓝牙通话设备（耳机、车载可同时出现）和手机麦克风。 */
fun listInputs(context: Context): List<InputOption> {
    val audio = context.getSystemService(AudioManager::class.java)
    val bluetooth = runCatching { audio.availableCommunicationDevices }.getOrDefault(emptyList())
        .filter { it.type in BT_TYPES }
        .map { InputOption(InputSelection(it.type, it.address, it.productName.toString()), isBluetooth = true) }
    val builtin = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        ?.let { InputOption(InputSelection(it.type, it.address, "手机麦克风"), isBluetooth = false) }
    return (bluetooth + listOfNotNull(builtin)).distinctBy { it.selection }
}

/** HyperOS 会把蓝牙地址隐藏成 00:00:00:00:00:00，所以同时比较设备名。 */
private fun InputSelection.matches(device: AudioDeviceInfo) =
    device.type == type && device.address == address &&
        (type !in BT_TYPES || device.productName.toString() == name)

/** 一路持续采集：读取 100 ms 帧交给分段器。 */
abstract class CaptureInput(protected val segmenter: Segmenter) {
    @Volatile protected var running = false
    private var worker: Thread? = null

    /** 启动门槛已通过、正在产出有效音频。 */
    @Volatile var ready = false
        protected set

    val levelDb get() = segmenter.levelDb

    fun start() {
        running = true
        worker = thread(name = javaClass.simpleName) { run() }
    }

    fun stop() {
        running = false
        worker?.join(2_000)
        worker = null
        ready = false
    }

    protected abstract fun run()

    protected fun pump(rec: AudioRecord, onWindow: () -> Unit = {}) {
        val frame = ShortArray(FRAME_SAMPLES)
        var lastWindow = SystemClock.elapsedRealtime()
        while (running) {
            val n = rec.read(frame, 0, frame.size)
            if (n > 0) segmenter.accept(frame, n, System.currentTimeMillis()) else SystemClock.sleep(20)
            val now = SystemClock.elapsedRealtime()
            if (now - lastWindow >= 10_000) {
                lastWindow = now
                onWindow()
            }
        }
        segmenter.flush()
    }

    protected fun format(): AudioFormat = AudioFormat.Builder()
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()

    protected fun bufferBytes() = max(
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4,
        SAMPLE_RATE * 2,
    )
}

/**
 * 麦克风：蓝牙设备通过 setCommunicationDevice 切到通话链路；用原始 MIC 音源，避免通话降噪把语音压低约 15 dB。
 * “仅在使用中允许”的授权刚生效时系统可能先给静音数据，所以先等到真实信号再开始分段。
 */
class MicInput(
    private val context: Context,
    private val selection: InputSelection,
    segmenter: Segmenter,
    private val onEvent: (String) -> Unit,
) : CaptureInput(segmenter) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val isBluetooth = selection.type in BT_TYPES
    @Volatile private var preferred: AudioDeviceInfo? = null
    @Volatile var routedName: String = selection.name
        private set

    override fun run() {
        route()
        val rec = awaitLiveInput() ?: return
        ready = true
        var lastReroute = 0L
        pump(rec) {
            routedName = rec.routedDevice?.productName?.toString() ?: "-"
            val now = SystemClock.elapsedRealtime()
            if (isBluetooth && audio.communicationDevice?.let(selection::matches) != true &&
                now - lastReroute >= REROUTE_INTERVAL_MS
            ) {
                lastReroute = now
                onEvent("${selection.name} 已断开，正在重连")
                if (route()) rec.preferredDevice = preferred
            }
        }
        rec.stop()
        rec.release()
        if (isBluetooth) {
            audio.clearCommunicationDevice()
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun route(): Boolean {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (!isBluetooth) {
            preferred = inputs.firstOrNull(selection::matches) ?: inputs.firstOrNull { it.type == selection.type }
            return preferred != null
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val comm = audio.availableCommunicationDevices.firstOrNull(selection::matches) ?: return false
        val ok = audio.setCommunicationDevice(comm)
        preferred = inputs.firstOrNull(selection::matches)
        Log.i(TAG, "route ${selection.name} ok=$ok input=${preferred?.productName}")
        return ok && preferred != null
    }

    @SuppressLint("MissingPermission")
    private fun build(): AudioRecord? = try {
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format())
            .setBufferSizeInBytes(bufferBytes())
            .build()
            .also {
                it.preferredDevice = preferred
                it.startRecording()
            }
    } catch (e: SecurityException) {
        onEvent("没有录音权限")
        null
    }

    private fun silenced(rec: AudioRecord): Boolean? = audio.activeRecordingConfigurations
        .firstOrNull { it.clientAudioSessionId == rec.audioSessionId }?.isClientSilenced

    private fun awaitLiveInput(): AudioRecord? {
        val frame = ShortArray(FRAME_SAMPLES)
        val begin = SystemClock.elapsedRealtime()
        var builtAt = begin
        var rec = build() ?: return null
        while (running) {
            val n = rec.read(frame, 0, frame.size)
            val live = silenced(rec) == false && n > 0 && (0 until n).any { frame[it].toInt() != 0 }
            val now = SystemClock.elapsedRealtime()
            if (live || now - begin >= STARTUP_TIMEOUT_MS) {
                Log.i(TAG, "mic ready=$live after ${now - begin} ms")
                return rec
            }
            if (now - builtAt >= STARTUP_REBUILD_MS) {
                rec.stop()
                rec.release()
                rec = build() ?: return null
                builtAt = SystemClock.elapsedRealtime()
            }
        }
        return rec
    }
}

/**
 * 手机正在播放的声音（耳机里的会议、视频、播客）。Android 只允许录制媒体、游戏和未分类的声音；
 * 通话和大多数会议 App 在会中使用通话类声音，系统不允许第三方录制。
 */
class PlaybackInput(private val projection: MediaProjection, segmenter: Segmenter) : CaptureInput(segmenter) {

    @SuppressLint("MissingPermission")
    override fun run() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format())
            .setBufferSizeInBytes(bufferBytes())
            .build()
        rec.startRecording()
        ready = true
        pump(rec)
        rec.stop()
        rec.release()
    }
}
