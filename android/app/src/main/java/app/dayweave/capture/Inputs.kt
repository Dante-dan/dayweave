package app.dayweave.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
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
private const val WINDOW_MS = 500L
/** 别的声音停下后，安静这么久才把耳机麦克风接回来。 */
private const val RECLAIM_AFTER_MS = 15_000L
private const val RETRY_INTERVAL_MS = 30_000L

data class InputOption(val selection: InputSelection, val isBluetooth: Boolean)

val InputSelection.isBluetooth get() = type in BT_TYPES

/** 可选的麦克风：手机麦克风（静默倾听，推荐）和每台在线的蓝牙通话设备。 */
fun listInputs(context: Context): List<InputOption> {
    val audio = context.getSystemService(AudioManager::class.java)
    val builtin = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        ?.let { InputOption(InputSelection(it.type, it.address, "手机麦克风"), isBluetooth = false) }
    val bluetooth = runCatching { audio.availableCommunicationDevices }.getOrDefault(emptyList())
        .filter { it.type in BT_TYPES }
        .map { InputOption(InputSelection(it.type, it.address, it.productName.toString()), isBluetooth = true) }
    return (listOfNotNull(builtin) + bluetooth).distinctBy { it.selection }
}

/** HyperOS 会把蓝牙地址隐藏成 00:00:00:00:00:00，所以同时比较设备名。 */
private fun InputSelection.matches(device: AudioDeviceInfo) =
    device.type == type && device.address == address &&
        (type !in BT_TYPES || device.productName.toString() == name)

/**
 * 麦克风持续采集，读取 100 ms 帧交给分段器。用原始 MIC 音源，避免通话降噪把语音压低约 15 dB。
 *
 * 做一个安静的旁听者：
 * - 不申请音频焦点、不播放任何声音；别的 App 要用麦克风时，系统会先让它用（我们这边只会收到静音）。
 * - 手机麦克风完全不影响耳机里的音乐和通话。
 * - 蓝牙耳机的麦克风只能走通话链路，会让耳机进入通话模式。所以只在没有别的声音时借用；
 *   一旦有音乐、播客、视频或通话，立刻还给系统，临时改用手机麦克风，安静一阵后再接回耳机。
 *
 * “仅在使用中允许”的授权刚生效时系统可能先给静音数据，所以先等到真实信号再开始分段。
 */
class MicInput(
    context: Context,
    private val selection: InputSelection,
    private val segmenter: Segmenter,
    private val scene: AudioScene,
    private val onEvent: (String) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val wantsBluetooth = selection.isBluetooth
    @Volatile private var running = false
    private var worker: Thread? = null

    /** 启动门槛已通过、正在产出有效音频。 */
    @Volatile var ready = false
        private set

    /** 此刻真正在用的麦克风，例如耳机让路时会显示手机麦克风。 */
    @Volatile var routedName: String = selection.name
        private set

    /** 耳机麦克风是否正让给别的声音。 */
    @Volatile var yielded = false
        private set

    /** 系统把麦克风先给了别的 App（例如通话录音、语音助手），我们暂时只收到静音。 */
    @Volatile var silenced = false
        private set

    val levelDb get() = segmenter.levelDb
    val speaking get() = segmenter.speaking

    private var onBluetooth = false

    fun start() {
        running = true
        worker = thread(name = "mic") { run() }
    }

    fun stop() {
        running = false
        worker?.join(2_000)
        worker = null
        ready = false
    }

    private fun builtin(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private fun run() {
        val rec = awaitLiveInput() ?: return
        ready = true
        var lastTry = SystemClock.elapsedRealtime()
        var quietSince = SystemClock.elapsedRealtime()
        val frame = ShortArray(FRAME_SAMPLES)
        var lastWindow = 0L
        while (running) {
            val n = rec.read(frame, 0, frame.size)
            if (n > 0) segmenter.accept(frame, n, System.currentTimeMillis()) else SystemClock.sleep(20)
            val now = SystemClock.elapsedRealtime()
            if (now - lastWindow < WINDOW_MS) continue
            lastWindow = now
            silenced = isSilenced(rec) == true
            if (scene.busy) quietSince = now
            if (wantsBluetooth) {
                when {
                    onBluetooth && scene.busy -> {
                        releaseBluetooth()
                        rec.preferredDevice = builtin()
                        yielded = true
                        onEvent("耳机正在放别的声音，已让出，暂用手机麦克风")
                    }
                    onBluetooth && audio.communicationDevice?.let(selection::matches) != true -> {
                        releaseBluetooth()
                        rec.preferredDevice = builtin()
                        onEvent("${selection.name} 已断开，暂用手机麦克风")
                    }
                    !onBluetooth && !scene.busy && now - quietSince >= RECLAIM_AFTER_MS && now - lastTry >= RETRY_INTERVAL_MS -> {
                        lastTry = now
                        if (acquireBluetooth()) {
                            rec.preferredDevice = bluetoothInput()
                            yielded = false
                            onEvent("已接回 ${selection.name}")
                        }
                    }
                }
            }
            routedName = rec.routedDevice?.productName?.toString()?.let { if (onBluetooth) it else "手机麦克风" } ?: routedName
        }
        segmenter.flush()
        rec.stop()
        rec.release()
        releaseBluetooth()
    }

    private fun bluetoothInput() = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull(selection::matches)

    /** 把耳机切到通话链路以使用它的麦克风。别人正在用声音时不抢。 */
    private fun acquireBluetooth(): Boolean {
        if (scene.busy) return false
        val comm = audio.availableCommunicationDevices.firstOrNull(selection::matches) ?: return false
        scene.ownCommunication = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = audio.setCommunicationDevice(comm) && bluetoothInput() != null
        Log.i(TAG, "acquire ${selection.name} ok=$ok")
        if (!ok) releaseBluetooth() else onBluetooth = true
        return ok
    }

    /** 归还耳机。只撤销我们自己的请求；别人在通话时不动系统音频模式。 */
    private fun releaseBluetooth() {
        if (!onBluetooth && !scene.ownCommunication) return
        audio.clearCommunicationDevice()
        if (!scene.calling && audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = AudioManager.MODE_NORMAL
        scene.ownCommunication = false
        onBluetooth = false
        Log.i(TAG, "release ${selection.name}")
    }

    @SuppressLint("MissingPermission")
    private fun build(): AudioRecord? = try {
        if (wantsBluetooth && !onBluetooth) {
            if (!acquireBluetooth()) yielded = scene.busy
        }
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(
                max(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4, SAMPLE_RATE * 2),
            )
            .build()
            .also {
                it.preferredDevice = if (onBluetooth) bluetoothInput() else builtin()
                it.startRecording()
            }
    } catch (e: SecurityException) {
        onEvent("没有录音权限")
        null
    }

    private fun isSilenced(rec: AudioRecord): Boolean? = audio.activeRecordingConfigurations
        .firstOrNull { it.clientAudioSessionId == rec.audioSessionId }?.isClientSilenced

    private fun awaitLiveInput(): AudioRecord? {
        val frame = ShortArray(FRAME_SAMPLES)
        val begin = SystemClock.elapsedRealtime()
        var builtAt = begin
        var rec = build() ?: return null
        while (running) {
            val n = rec.read(frame, 0, frame.size)
            val live = isSilenced(rec) == false && n > 0 && (0 until n).any { frame[it].toInt() != 0 }
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
        rec.stop()
        rec.release()
        releaseBluetooth()
        return null
    }
}
