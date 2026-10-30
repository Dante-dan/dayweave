package app.dayweave.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000
private const val TAG = "DayweaveProbe"
private const val FRAME_SAMPLES = SAMPLE_RATE / 10 // 100 ms
private const val WINDOW_MS = 10_000L
private const val REROUTE_INTERVAL_MS = 30_000L
private const val START_SAMPLE_S = 30
private const val PERIODIC_SAMPLE_S = 20
private const val PERIODIC_SAMPLE_EVERY_MS = 15 * 60_000L

// “仅在使用中允许”的录音授权在刚启动时可能还没生效，系统会先给静音数据。
private const val STARTUP_TIMEOUT_MS = 10_000L
private const val STARTUP_REBUILD_MS = 3_000L

private val BT_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)

/** 音源：VOICE_COMMUNICATION 带系统降噪和回声消除，MIC 是原始麦克风，用来对比。 */
enum class CaptureSource(val androidSource: Int) {
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    MIC(MediaRecorder.AudioSource.MIC),
}

/**
 * 用户选中的输入设备。耳机和车载可以同时在线；HyperOS 上蓝牙地址会被隐藏成 00:00:00:00:00:00，
 * 所以同时比较设备名。
 */
data class InputChoice(val type: Int, val address: String, val name: String) {
    val isBluetooth get() = type in BT_TYPES
    val label get() = "${typeName(type)}:$name"

    fun matches(device: AudioDeviceInfo) =
        device.type == type && device.address == address && (!isBluetooth || device.productName.toString() == name)
}

data class ProbeState(
    val running: Boolean = false,
    val choice: InputChoice? = null,
    val source: CaptureSource = CaptureSource.VOICE_COMMUNICATION,
    val ready: Boolean = false,
    val elapsedSec: Long = 0,
    val meanDbfs: Double? = null,
    val liveDbfs: Double? = null,
    val silenced: Boolean? = null,
    val routedInput: String = "-",
    val commDevice: String = "-",
    val sampling: String? = null,
    val sessionDir: String? = null,
    val lastEvent: String = "",
)

/**
 * 持续采集探针：只采集、测量和记录，不做转写。
 * 回答的问题：选定输入能否在锁屏下长时间持续收音；路由何时丢失、能否恢复；系统是否静音了本 App；耗电如何。
 */
class ProbeService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_SAMPLE = "sample"
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_SOURCE = "source"

        private val _state = MutableStateFlow(ProbeState())
        val state: StateFlow<ProbeState> = _state

        private const val CHANNEL_ID = "probe"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var audio: AudioManager
    private lateinit var power: PowerManager
    private lateinit var battery: BatteryManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var files: SessionFiles? = null
    private lateinit var choice: InputChoice
    private var source = CaptureSource.VOICE_COMMUNICATION

    @Volatile private var running = false
    @Volatile private var sampleRequestS = 0
    @Volatile private var preferredInput: AudioDeviceInfo? = null
    @Volatile private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private val commListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        event("communication device -> ${describe(device)}")
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            added.forEach { event("device added: ${describe(it)} (sink=${it.isSink})") }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            removed.forEach { event("device removed: ${describe(it)} (sink=${it.isSink})") }
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val mine = ownConfig(configs) ?: return
            event("recording config: silenced=${mine.isClientSilenced} device=${describe(mine.audioDevice)} others=${configs.size - 1}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        power = getSystemService(PowerManager::class.java)
        battery = getSystemService(BatteryManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "采集探针", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) {
                choice = InputChoice(
                    type = intent.getIntExtra(EXTRA_DEVICE_TYPE, AudioDeviceInfo.TYPE_BUILTIN_MIC),
                    address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS).orEmpty(),
                    name = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty(),
                )
                source = CaptureSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: CaptureSource.VOICE_COMMUNICATION.name)
                start()
            }
            ACTION_SAMPLE -> if (running) sampleRequestS = START_SAMPLE_S
            ACTION_STOP -> stopProbe()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopProbe()
        callbackExecutor.shutdown()
        super.onDestroy()
    }

    private fun start() {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        running = true
        files = SessionFiles(this)
        _state.value = ProbeState(running = true, choice = choice, source = source, sessionDir = files?.dir?.absolutePath)

        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dayweave:probe").apply {
            acquire(24 * 60 * 60_000L)
        }
        audio.addOnCommunicationDeviceChangedListener(callbackExecutor, commListener)
        audio.registerAudioDeviceCallback(deviceCallback, null)
        audio.registerAudioRecordingCallback(recordingCallback, null)

        event("start choice=${choice.label}@${choice.address} source=$source model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}")
        audio.availableCommunicationDevices.forEach { event("available comm device: ${describe(it)}@${it.address}") }
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { event("input device: ${describe(it)}@${it.address}") }

        route()
        captureThread = thread(name = "probe-capture") { captureLoop() }
    }

    private fun stopProbe() {
        if (!running) return
        running = false
        captureThread?.join(2_000)
        captureThread = null
        runCatching { audio.removeOnCommunicationDeviceChangedListener(commListener) }
        runCatching { audio.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { audio.unregisterAudioRecordingCallback(recordingCallback) }
        if (choice.isBluetooth) {
            audio.clearCommunicationDevice()
            audio.mode = AudioManager.MODE_NORMAL
        }
        event("stop")
        files?.close()
        files = null
        wakeLock?.takeIf { it.isHeld }?.release()
        _state.update { it.copy(running = false, ready = false, sampling = null) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 把通话设备切到选中的蓝牙设备，或使用手机麦克风。返回是否拿到了选中的输入。 */
    private fun route(): Boolean {
        if (!choice.isBluetooth) {
            preferredInput = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull(choice::matches)
                ?: audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == choice.type }
            event("route ${choice.label} -> ${describe(preferredInput)}")
            recorder?.preferredDevice = preferredInput
            return preferredInput != null
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val comm = audio.availableCommunicationDevices.firstOrNull(choice::matches)
        if (comm == null) {
            event("route ${choice.label} failed: device not available")
            preferredInput = null
            return false
        }
        val ok = audio.setCommunicationDevice(comm)
        preferredInput = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull(choice::matches)
        event("route setCommunicationDevice(${describe(comm)}@${comm.address})=$ok input=${describe(preferredInput)}")
        recorder?.preferredDevice = preferredInput
        return ok && preferredInput != null
    }

    private fun buildRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source.androidSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuf * 4, SAMPLE_RATE * 2))
                .build()
        } catch (e: SecurityException) {
            event("AudioRecord denied: $e")
            return null
        }
        rec.preferredDevice = preferredInput
        rec.addOnRoutingChangedListener({ r -> event("record routed -> ${describe(r.routedDevice)}") }, null)
        rec.startRecording()
        event("recording state=${rec.recordingState} session=${rec.audioSessionId}")
        recorder = rec
        return rec
    }

    private fun releaseRecorder(rec: AudioRecord) {
        recorder = null
        runCatching { rec.stop() }
        rec.release()
    }

    /**
     * 启动门槛：直到系统不再静音本 App、并读到非零数据，才开始正式计时。
     * 一直不满足时每 3 秒重建一次录音，最多等 10 秒。
     */
    private fun awaitLiveInput(frame: ShortArray): AudioRecord? {
        val begin = SystemClock.elapsedRealtime()
        var rec = buildRecorder() ?: return null
        var builtAt = begin
        while (running) {
            val n = rec.read(frame, 0, frame.size)
            val silenced = isSilenced(rec)
            val hasSignal = n > 0 && (0 until n).any { frame[it].toInt() != 0 }
            val now = SystemClock.elapsedRealtime()
            if (silenced == false && hasSignal) {
                event("capture ready after ${now - begin} ms")
                return rec
            }
            if (now - begin >= STARTUP_TIMEOUT_MS) {
                event("capture not ready after ${now - begin} ms (silenced=$silenced signal=$hasSignal), continuing anyway")
                return rec
            }
            if (now - builtAt >= STARTUP_REBUILD_MS) {
                event("startup: silenced=$silenced signal=$hasSignal, rebuilding recorder")
                releaseRecorder(rec)
                rec = buildRecorder() ?: return null
                builtAt = SystemClock.elapsedRealtime()
            }
            if (n <= 0) SystemClock.sleep(20)
        }
        return rec
    }

    private fun captureLoop() {
        val frame = ShortArray(FRAME_SAMPLES)
        val rec = awaitLiveInput(frame) ?: return
        _state.update { it.copy(ready = true) }
        sampleRequestS = START_SAMPLE_S

        val startedAt = SystemClock.elapsedRealtime()
        var windowStart = startedAt
        var lastReroute = 0L
        var lastLiveLog = 0L
        var nextPeriodicSample = startedAt + PERIODIC_SAMPLE_EVERY_MS
        var sumSq = 0.0
        var maxDb = -120.0
        var frames = 0
        var zeroFrames = 0
        var samplesRead = 0L
        var readErrors = 0
        var silencedWindows = 0
        var checks = 0
        var wav: WavWriter? = null
        var wavRemaining = 0

        while (running) {
            val n = rec.read(frame, 0, frame.size)
            if (n <= 0) {
                readErrors++
                if (readErrors % 50 == 1) event("read error $n")
                SystemClock.sleep(20)
            } else {
                samplesRead += n
                var frameSq = 0.0
                var allZero = true
                for (i in 0 until n) {
                    val s = frame[i].toDouble()
                    frameSq += s * s
                    if (frame[i].toInt() != 0) allZero = false
                }
                sumSq += frameSq
                frames++
                if (allZero) zeroFrames++
                val frameDb = dbfs(frameSq / n)
                maxDb = max(maxDb, frameDb)

                // 每 0.5 秒输出一次实时电平，便于用 adb logcat 现场观察。
                val t = SystemClock.elapsedRealtime()
                if (t - lastLiveLog >= 500) {
                    lastLiveLog = t
                    Log.i(TAG, "live %.1f dBFS".format(frameDb))
                    _state.update { it.copy(liveDbfs = frameDb) }
                }

                if (wav == null && sampleRequestS > 0) {
                    wavRemaining = sampleRequestS * SAMPLE_RATE
                    sampleRequestS = 0
                    wav = files?.newSample("${typeName(choice.type)}-${source.name.lowercase()}")
                    _state.update { it.copy(sampling = wav?.file?.name) }
                }
                wav?.let {
                    val count = minOf(n, wavRemaining)
                    it.write(frame, count)
                    wavRemaining -= count
                    if (wavRemaining <= 0) {
                        it.close()
                        event("sample saved ${it.file.name}")
                        wav = null
                        _state.update { s -> s.copy(sampling = null) }
                    }
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (frames % 10 == 0) {
                checks++
                if (isSilenced(rec) == true) silencedWindows++
            }
            if (now >= nextPeriodicSample) {
                if (sampleRequestS == 0 && wav == null) sampleRequestS = PERIODIC_SAMPLE_S
                nextPeriodicSample = now + PERIODIC_SAMPLE_EVERY_MS
            }
            if (now - windowStart < WINDOW_MS) continue

            val totalSamples = samplesRead.coerceAtLeast(1)
            val meanDb = dbfs(sumSq / totalSamples)
            val routed = describe(rec.routedDevice)
            val comm = describe(audio.communicationDevice)
            val expected = (now - windowStart) * SAMPLE_RATE / 1000
            val configs = audio.activeRecordingConfigurations
            val silencedRatio = if (checks == 0) 0.0 else silencedWindows.toDouble() / checks
            files?.metric(
                listOf(
                    LocalTime.now(), (now - startedAt) / 1000, source, routed, comm, audio.mode,
                    "%.1f".format(meanDb), "%.1f".format(maxDb),
                    "%.2f".format(if (frames == 0) 1.0 else zeroFrames.toDouble() / frames),
                    "%.2f".format(silencedRatio), (configs.size - 1).coerceAtLeast(0),
                    samplesRead, expected, readErrors,
                    battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                    battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                    power.isInteractive,
                ),
            )
            Log.i(TAG, "window mean=%.1f max=%.1f silenced=%.2f routed=%s".format(meanDb, maxDb, silencedRatio, routed))
            _state.update {
                it.copy(
                    elapsedSec = (now - startedAt) / 1000, meanDbfs = meanDb, silenced = silencedRatio > 0.5,
                    routedInput = routed, commDevice = comm,
                )
            }

            // 选中的蓝牙设备丢失时定期尝试恢复；只连回用户选的那台，不会跳到别的蓝牙设备。
            if (choice.isBluetooth && audio.communicationDevice?.let(choice::matches) != true &&
                now - lastReroute >= REROUTE_INTERVAL_MS
            ) {
                lastReroute = now
                event("${choice.label} route lost, retrying")
                route()
            }

            windowStart = now
            sumSq = 0.0
            maxDb = -120.0
            frames = 0
            zeroFrames = 0
            samplesRead = 0
            readErrors = 0
            silencedWindows = 0
            checks = 0
        }

        wav?.close()
        releaseRecorder(rec)
    }

    private fun ownConfig(configs: List<AudioRecordingConfiguration>): AudioRecordingConfiguration? {
        val session = recorder?.audioSessionId ?: return null
        return configs.firstOrNull { it.clientAudioSessionId == session }
    }

    /** 系统是否把本 App 的录音静音了。null 表示查不到本 App 的录音配置。 */
    private fun isSilenced(rec: AudioRecord): Boolean? =
        audio.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == rec.audioSessionId }?.isClientSilenced

    private fun dbfs(meanSquare: Double): Double =
        if (meanSquare <= 0) -120.0 else 20 * log10(sqrt(meanSquare) / Short.MAX_VALUE)

    private fun event(message: String) {
        Log.i(TAG, message)
        files?.event(message)
        _state.update { it.copy(lastEvent = "${LocalTime.now().withNano(0)} $message") }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Dayweave 探针正在收音")
            .setContentText("仅用于设备采集验证，录音样本保存在本机")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}

fun describe(device: AudioDeviceInfo?): String =
    device?.let { "${typeName(it.type)}:${it.productName}" } ?: "none"

fun typeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
    AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
    else -> "type$type"
}
