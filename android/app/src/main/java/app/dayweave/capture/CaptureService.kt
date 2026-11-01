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
import app.dayweave.core.InputSelection
import app.dayweave.core.Scene
import app.dayweave.core.SourceKind
import app.dayweave.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import app.dayweave.core.tr
import app.dayweave.core.deviceLabel

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var inputWatch: Job? = null
    /** 当前麦克风对应的选择。暂停中改了选择，继续时用新的。 */
    private var micSelection: InputSelection? = null
    private var scene: AudioScene? = null
    private var detector: SpeechDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var shownNotification = ""
    private var ticks = 0

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
            // 记录中每两分钟看一次有没有新的通话录音。
            if (++ticks % 1500 == 0) applicationContext.graph.callRecordings.scan()
            if (_state.value.running) main.postDelayed(this, 80)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("正在记录", "Recording"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = tr("记录进行中时常驻，可在这里暂停或结束", "Shown while recording. Pause or stop from here")
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
        scope.cancel()
        super.onDestroy()
    }

    private fun start() {
        startForeground(NOTIFICATION_ID, notification(CaptureState(running = true)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val graph = applicationContext.graph
        val selection = graph.settings.input.value ?: listInputs(this).firstOrNull()?.selection
        if (selection == null) {
            _state.value = CaptureState(notice = tr("没有可用的麦克风", "No microphone available"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dayweave:capture")
            .apply { acquire(24 * 60 * 60_000L) }
        detector = SpeechDetector(this)
        scene = AudioScene(this, graph.archive) { ended ->
            // 通话结束后，系统录音一般几秒内写完，稍等再导入。
            if (ended.kind == app.dayweave.core.SceneKind.CALL) main.postDelayed({ graph.callRecordings.scan() }, 30_000)
        }.also { it.start() }
        _state.value = CaptureState(running = true, startedAt = System.currentTimeMillis(), micName = selection.name)
        startMic()
        // 记录中也能换麦克风：手机麦克风、各个蓝牙设备之间随时切换。
        inputWatch = scope.launch {
            graph.settings.input.filterNotNull().distinctUntilChanged().collect { if (it != micSelection) switchMic(it) }
        }
        main.post(ticker)
        requestTileUpdate()
    }

    /** 换到新的麦克风：当前这段话在切换处收尾提交，新设备就绪后接着听。暂停中只记下选择，继续时生效。 */
    private fun switchMic(selection: InputSelection) {
        if (!_state.value.running || _state.value.paused) return
        mic?.stop()
        mic = null
        _state.update {
            it.copy(
                micName = selection.name, micReady = false, micLevel = -90.0, micSpeaking = false, yielded = false, silenced = false,
                notice = tr("已切换到 ${deviceLabel(selection.name)}", "Switched to ${deviceLabel(selection.name)}"),
            )
        }
        startMic()
        refreshNotification()
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
        micSelection = selection
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
        inputWatch?.cancel()
        inputWatch = null
        mic?.stop()
        mic = null
        micSelection = null
        scene?.stop()
        scene = null
        detector?.release()
        detector = null
        wakeLock?.takeIf { it.isHeld }?.release()
        _state.value = CaptureState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        requestTileUpdate()
        showStandby(this)
    }

    private fun requestTileUpdate() {
        TileService.requestListeningState(this, ComponentName(this, CaptureTile::class.java))
    }

    /** 只在文字变化时更新通知，避免 80 ms 一次地刷新。 */
    private fun refreshNotification() {
        val s = _state.value
        if (!s.running) return
        val key = "${app.dayweave.core.Lang.english}|${s.paused}|${s.yielded}|${s.silenced}|${s.scene?.label}|${s.micName}|${s.micReady}"
        if (key == shownNotification) return
        shownNotification = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    private fun notification(s: CaptureState): Notification {
        val title = when {
            s.paused -> tr("拾日已暂停", "Dayweave is paused")
            s.silenced -> tr("拾日在旁听 · 麦克风被其他 App 占用", "Dayweave is waiting · another app has the mic")
            else -> tr("拾日正在安静地聆听", "Dayweave is quietly listening")
        }
        val text = when {
            s.paused -> tr("轻触右侧按钮继续", "Tap the button to resume")
            s.scene != null && s.yielded -> tr("${s.scene.label} · 已把耳机让出，用手机麦克风", "${s.scene.label} · earbuds handed back, using the phone mic")
            s.scene != null -> tr("${s.scene.label} · 只记录人声，不录播放的声音", "${s.scene.label} · voices only, playback is never recorded")
            else -> tr(
                "${s.micName?.let(::deviceLabel) ?: "麦克风"} · 只转写人声，文字存好即删除音频",
                "${s.micName?.let(::deviceLabel) ?: "Microphone"} · voices only, audio deleted once transcribed",
            )
        }
        val toggle = if (s.paused) service(ACTION_RESUME) else service(ACTION_PAUSE)
        return controls(this, title, text, toggleIcon = if (s.paused) R.drawable.ic_stat_play else R.drawable.ic_stat_pause, toggle = toggle, stop = service(ACTION_STOP))
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun service(action: String) =
        PendingIntent.getService(this, action.hashCode(), Intent(this, CaptureService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
}

/**
 * 通知栏里的记录控件。收起状态下就有按钮：记录中是“暂停/继续”和“结束”，没在记录时是一个“开始”。
 * 自定义布局套在系统的通知外框里，字体和深浅色跟随系统。
 */
private fun controls(
    context: Context,
    title: String,
    text: String,
    toggleIcon: Int,
    toggle: PendingIntent,
    stop: PendingIntent?,
): Notification.Builder {
    val views = android.widget.RemoteViews(context.packageName, R.layout.notification_capture).apply {
        setTextViewText(R.id.title, title)
        setTextViewText(R.id.text, text)
        setImageViewResource(R.id.toggle, toggleIcon)
        setOnClickPendingIntent(R.id.toggle, toggle)
        if (stop != null) setOnClickPendingIntent(R.id.stop, stop) else setViewVisibility(R.id.stop, android.view.View.GONE)
    }
    val open = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).putExtra("tab", "capture").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Builder(context, CAPTURE_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_listen)
        .setContentTitle(title)
        .setContentText(text)
        .setCustomContentView(views)
        .setStyle(Notification.DecoratedCustomViewStyle())
        .setContentIntent(open)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(Notification.CATEGORY_SERVICE)
}

private const val CAPTURE_CHANNEL = "capture"
private const val CAPTURE_NOTIFICATION = 1

/** 没在记录时，通知栏留一个“开始”入口（可在设置里关掉）。开始要借一个透明界面，因为后台不能直接启动麦克风。 */
fun showStandby(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (CaptureService.state.value.running) return
    if (!context.graph.settings.standby.value) {
        manager.cancel(CAPTURE_NOTIFICATION)
        return
    }
    manager.createNotificationChannel(
        NotificationChannel(CAPTURE_CHANNEL, tr("正在记录", "Recording"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
    )
    val start = PendingIntent.getActivity(
        context, 7, Intent(context, StartCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
    )
    val n = controls(context, tr("拾日", "Dayweave"), tr("轻触开始，安静地拾起今天说过的话", "Tap to start quietly gathering today's words"), R.drawable.ic_stat_play, start, null)
        .setOngoing(true)
        .build()
    runCatching { manager.notify(CAPTURE_NOTIFICATION, n) }
}
