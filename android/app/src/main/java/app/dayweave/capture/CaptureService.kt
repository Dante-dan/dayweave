package app.dayweave.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.quicksettings.TileService
import app.dayweave.MainActivity
import app.dayweave.R
import app.dayweave.core.Scene
import app.dayweave.core.SourceKind
import app.dayweave.graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class CaptureState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val startedAt: Long = 0,
    val micName: String? = null,
    val micReady: Boolean = false,
    val micLevel: Double = -90.0,
    val micSpeaking: Boolean = false,
    /** 耳机麦克风正让给别的声音。 */
    val yielded: Boolean = false,
    /** 系统把麦克风先给了别的 App。 */
    val silenced: Boolean = false,
    val scene: Scene? = null,
    val notice: String? = null,
) {
    val listening get() = running && !paused
}

/**
 * 全天采集前台服务。片段结束后交给转写流水线，自身不做转写。
 * 通知栏常驻一条低打扰的通知，带“暂停 / 继续 / 结束”，App 可以一直待在后台。
 */
class CaptureService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow(CaptureState())
        val state: StateFlow<CaptureState> = _state

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CaptureService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) = send(context, ACTION_STOP)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)

        private fun send(context: Context, action: String) {
            if (_state.value.running) context.startService(Intent(context, CaptureService::class.java).setAction(action))
        }
    }

    private var mic: MicInput? = null
    private var scene: AudioScene? = null
    private var detector: SpeechDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var shownNotification = ""

    private val ticker = object : Runnable {
        override fun run() {
            val m = mic
            _state.update {
                it.copy(
                    micReady = m?.ready == true,
                    micLevel = m?.levelDb ?: -90.0,
                    micSpeaking = m?.let { input -> input.ready && input.speaking } == true,
                    micName = m?.routedName ?: it.micName,
                    yielded = m?.yielded == true,
                    silenced = m?.silenced == true,
                    scene = scene?.current?.value,
                )
            }
            refreshNotification()
            if (_state.value.running) main.postDelayed(this, 80)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "正在记录", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "记录进行中时常驻，可在这里暂停或结束"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!_state.value.running) start()
            ACTION_STOP -> stopCapture()
            ACTION_PAUSE -> pauseCapture()
            ACTION_RESUME -> resumeCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun start() {
        startForeground(NOTIFICATION_ID, notification(CaptureState(running = true)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val graph = applicationContext.graph
        val selection = graph.settings.input.value ?: listInputs(this).firstOrNull()?.selection
        if (selection == null) {
            _state.value = CaptureState(notice = "没有可用的麦克风")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dayweave:capture")
            .apply { acquire(24 * 60 * 60_000L) }
        detector = SpeechDetector(this)
        scene = AudioScene(this, graph.archive).also { it.start() }
        _state.value = CaptureState(running = true, startedAt = System.currentTimeMillis(), micName = selection.name)
        startMic()
        main.post(ticker)
        requestTileUpdate()
    }

    private fun startMic() {
        val graph = applicationContext.graph
        val selection = graph.settings.input.value ?: listInputs(this).firstOrNull()?.selection ?: return
        val dir = File(cacheDir, "segments").apply { mkdirs() }
        val s = scene ?: return
        lateinit var input: MicInput
        val segmenter = Segmenter(
            source = SourceKind.MIC,
            dir = dir,
            detector = detector ?: return,
            device = { input.routedName },
            scene = { s.current.value },
        ) { segment, file -> graph.pipeline.submit(segment, file) }
        input = MicInput(this, selection, segmenter, s) { notice -> _state.update { it.copy(notice = notice) } }
        mic = input.also { it.start() }
    }

    private fun pauseCapture() {
        if (!_state.value.running || _state.value.paused) return
        mic?.stop()
        mic = null
        _state.update { it.copy(paused = true, micReady = false, micLevel = -90.0, micSpeaking = false, notice = null) }
        refreshNotification()
        requestTileUpdate()
    }

    private fun resumeCapture() {
        if (!_state.value.running || !_state.value.paused) return
        _state.update { it.copy(paused = false) }
        startMic()
        refreshNotification()
        requestTileUpdate()
    }

    private fun stopCapture() {
        if (!_state.value.running) return
        main.removeCallbacks(ticker)
        mic?.stop()
        mic = null
        scene?.stop()
        scene = null
        detector?.release()
        detector = null
        wakeLock?.takeIf { it.isHeld }?.release()
        _state.value = CaptureState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        requestTileUpdate()
    }

    private fun requestTileUpdate() {
        TileService.requestListeningState(this, ComponentName(this, CaptureTile::class.java))
    }

    /** 只在文字变化时更新通知，避免 80 ms 一次地刷新。 */
    private fun refreshNotification() {
        val s = _state.value
        if (!s.running) return
        val key = "${s.paused}|${s.yielded}|${s.silenced}|${s.scene?.label}|${s.micName}|${s.micReady}"
        if (key == shownNotification) return
        shownNotification = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    private fun action(action: String, label: String, icon: Int): Notification.Action {
        val pi = PendingIntent.getService(this, action.hashCode(), Intent(this, CaptureService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun notification(s: CaptureState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra("tab", "capture").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when {
            s.paused -> "拾日已暂停"
            s.silenced -> "拾日在旁听 · 麦克风被其他 App 占用"
            else -> "拾日正在安静地聆听"
        }
        val text = when {
            s.paused -> "轻触“继续”恢复记录"
            s.scene != null && s.yielded -> "${s.scene.label} · 已把耳机让出，用手机麦克风"
            s.scene != null -> "${s.scene.label} · 只记录人声，不录播放的声音"
            else -> "${s.micName ?: "麦克风"} · 只转写人声，文字存好即删除音频"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_listen)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(if (s.paused) action(ACTION_RESUME, "继续", R.drawable.ic_stat_listen) else action(ACTION_PAUSE, "暂停", R.drawable.ic_stat_pause))
            .addAction(action(ACTION_STOP, "结束", R.drawable.ic_stat_stop))
            .build()
    }
}
