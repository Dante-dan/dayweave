package app.dayweave.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.IntentCompat
import app.dayweave.MainActivity
import app.dayweave.core.SourceKind
import app.dayweave.graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class CaptureState(
    val running: Boolean = false,
    val startedAt: Long = 0,
    val micName: String? = null,
    val micReady: Boolean = false,
    val micLevel: Double = -90.0,
    val micSpeaking: Boolean = false,
    val playbackOn: Boolean = false,
    val playbackLevel: Double = -90.0,
    val playbackSpeaking: Boolean = false,
    val notice: String? = null,
)

/** 全天采集前台服务。片段结束后交给转写流水线，自身不做转写。 */
class CaptureService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_PROJECTION = "projection"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow(CaptureState())
        val state: StateFlow<CaptureState> = _state

        /** projectionData 为 null 时只采集麦克风。 */
        fun start(context: Context, projectionData: Intent?) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_START)
            projectionData?.let { intent.putExtra(EXTRA_PROJECTION, it) }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private var mic: MicInput? = null
    private var playback: PlaybackInput? = null
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val m = mic
            val p = playback
            _state.update {
                it.copy(
                    micReady = m?.ready == true,
                    micLevel = m?.levelDb ?: -90.0,
                    micSpeaking = m?.let { input -> input.ready && input.levelDb > -45 } == true,
                    micName = m?.routedName ?: it.micName,
                    playbackOn = p?.ready == true,
                    playbackLevel = p?.levelDb ?: -90.0,
                    playbackSpeaking = p?.let { input -> input.ready && input.levelDb > -50 } == true,
                )
            }
            if (_state.value.running) main.postDelayed(this, 80)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "正在记录", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!_state.value.running) {
                start(intent.let { IntentCompat.getParcelableExtra(it, EXTRA_PROJECTION, Intent::class.java) })
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun start(projectionData: Intent?) {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (projectionData != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        startForeground(NOTIFICATION_ID, notification(), type)

        val graph = applicationContext.graph
        val selection = graph.settings.input.value ?: listInputs(this).firstOrNull()?.selection
        if (selection == null) {
            _state.value = CaptureState(notice = "没有可用的麦克风")
            stopSelf()
            return
        }
        val dir = File(cacheDir, "segments").apply { mkdirs() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dayweave:capture")
            .apply { acquire(24 * 60 * 60_000L) }

        _state.value = CaptureState(running = true, startedAt = System.currentTimeMillis(), micName = selection.name)
        mic = MicInput(this, selection, segmenter(SourceKind.MIC, dir) { mic?.routedName ?: selection.name }) { notice ->
            _state.update { it.copy(notice = notice) }
        }.also { it.start() }

        if (projectionData != null) startPlayback(projectionData, dir)
        main.post(ticker)
    }

    private fun startPlayback(data: Intent, dir: File) {
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data) ?: return
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                playback?.stop()
                playback = null
                _state.update { it.copy(playbackOn = false, notice = "已停止采集播放声音") }
            }
        }, main)
        projection = p
        playback = PlaybackInput(p, segmenter(SourceKind.PLAYBACK, dir) { "手机播放" }).also { it.start() }
    }

    private fun segmenter(source: SourceKind, dir: File, device: () -> String) =
        Segmenter(source, dir, device) { segment, file -> applicationContext.graph.pipeline.submit(segment, file) }

    private fun stopCapture() {
        if (!_state.value.running) return
        main.removeCallbacks(ticker)
        mic?.stop()
        playback?.stop()
        projection?.stop()
        mic = null
        playback = null
        projection = null
        wakeLock?.takeIf { it.isHeld }?.release()
        _state.value = CaptureState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("拾日正在记录")
            .setContentText("录音只用于转写，文字保存后即删除音频")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
