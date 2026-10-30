package app.dayweave.capture

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import app.dayweave.core.ArchiveStore
import app.dayweave.core.Listening
import app.dayweave.core.Scene
import app.dayweave.core.SceneKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import app.dayweave.core.tr

private const val POLL_MS = 1_500L
/** 场景持续这么久才算切换，避免切歌、广告间隙来回跳。 */
private const val SETTLE_MS = 4_000L
private const val MIN_SPAN_MS = 20_000L
private const val SAVE_EVERY_MS = 60_000L

/** 常见 App 的归类。没开通知使用权时拿不到包名，只能按系统公开的内容类型判断。 */
private val KNOWN = mapOf(
    "com.netease.cloudmusic" to (SceneKind.MUSIC to tr("网易云音乐", "NetEase Cloud Music")),
    "com.tencent.qqmusic" to (SceneKind.MUSIC to tr("QQ 音乐", "QQ Music")),
    "com.kugou.android" to (SceneKind.MUSIC to tr("酷狗音乐", "Kugou Music")),
    "cn.kuwo.player" to (SceneKind.MUSIC to tr("酷我音乐", "Kuwo Music")),
    "com.luna.music" to (SceneKind.MUSIC to tr("汽水音乐", "Qishui Music")),
    "com.miui.player" to (SceneKind.MUSIC to tr("小米音乐", "Xiaomi Music")),
    "com.spotify.music" to (SceneKind.MUSIC to "Spotify"),
    "com.apple.android.music" to (SceneKind.MUSIC to "Apple Music"),
    "com.google.android.apps.youtube.music" to (SceneKind.MUSIC to "YouTube Music"),
    "app.podcast.cosmos" to (SceneKind.PODCAST to tr("小宇宙", "Xiaoyuzhou")),
    "com.ximalaya.ting.android" to (SceneKind.PODCAST to tr("喜马拉雅", "Ximalaya")),
    "au.com.shiftyjelly.pocketcasts" to (SceneKind.PODCAST to "Pocket Casts"),
    "com.google.android.apps.podcasts" to (SceneKind.PODCAST to "Google Podcasts"),
    "tv.danmaku.bili" to (SceneKind.VIDEO to tr("哔哩哔哩", "Bilibili")),
    "com.ss.android.ugc.aweme" to (SceneKind.VIDEO to tr("抖音", "Douyin")),
    "com.google.android.youtube" to (SceneKind.VIDEO to "YouTube"),
    "com.tencent.qqlive" to (SceneKind.VIDEO to tr("腾讯视频", "Tencent Video")),
    "com.youku.phone" to (SceneKind.VIDEO to tr("优酷", "Youku")),
    "com.qiyi.video" to (SceneKind.VIDEO to tr("爱奇艺", "iQIYI")),
    "com.xingin.xhs" to (SceneKind.VIDEO to tr("小红书", "Xiaohongshu")),
    "com.tencent.wemeet.app" to (SceneKind.CALL to tr("腾讯会议", "Tencent Meeting")),
    "us.zoom.videomeetings" to (SceneKind.CALL to "Zoom"),
    "com.ss.android.lark" to (SceneKind.CALL to tr("飞书", "Lark")),
    "com.alibaba.android.rimet" to (SceneKind.CALL to tr("钉钉", "DingTalk")),
    "com.microsoft.teams" to (SceneKind.CALL to "Teams"),
    "com.tencent.mm" to (SceneKind.CALL to tr("微信", "WeChat")),
)

/** 只用来读取“正在播放”的媒体会话；不读取、不保存通知内容。用户可以不开。 */
class MediaListener : NotificationListenerService()

fun mediaAccessGranted(context: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * 观察手机此刻在放什么：音乐、播客、视频，还是通话 / 会议。只读系统公开的播放状态，不录制任何播放的声音。
 * 同时告诉蓝牙麦克风什么时候该让路：别的 App 一出声，就把耳机还给它。
 */
class AudioScene(
    private val context: Context,
    private val archive: ArchiveStore,
    /** 一个场景结束时回调，例如通话结束后去导入通话录音。 */
    private val onEnded: (Scene) -> Unit = {},
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _current = MutableStateFlow<Scene?>(null)
    /** 稳定后的场景，没有在放东西时为 null。 */
    val current: StateFlow<Scene?> = _current

    /** 其他 App 正在出声或通话。蓝牙麦克风此时应当让路。 */
    @Volatile var busy = false
        private set

    /** 此刻（未经防抖）是否有别人在通话或开会。这时不能改系统的音频模式，免得打断对方。 */
    @Volatile var calling = false
        private set

    /** 我们自己为了蓝牙麦克风把系统切到了通话模式，这时的通话模式不算别人在通话。 */
    @Volatile var ownCommunication = false

    private var candidate: Scene? = null
    private var candidateSince = 0L
    private var span: Listening? = null
    private var lastSaved = 0L

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = update()
    }
    private val poll = object : Runnable {
        override fun run() {
            update()
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        audio.registerAudioPlaybackCallback(callback, main)
        main.post(poll)
    }

    fun stop() {
        audio.unregisterAudioPlaybackCallback(callback)
        main.removeCallbacks(poll)
        closeSpan(System.currentTimeMillis())
        _current.value = null
        candidate = null
        busy = false
        calling = false
    }

    private fun update() {
        val now = System.currentTimeMillis()
        val raw = detect()
        busy = raw != null
        calling = raw?.kind == SceneKind.CALL
        if (raw != candidate) {
            candidate = raw
            candidateSince = now
        }
        if (candidate != _current.value && now - candidateSince >= SETTLE_MS) {
            closeSpan(now - SETTLE_MS)
            _current.value = candidate
            span = candidate?.let { Listening(UUID.randomUUID().toString(), it, now - SETTLE_MS, now) }
        }
        span?.let {
            span = it.copy(endedAt = now)
            if (now - lastSaved >= SAVE_EVERY_MS && it.durationMs >= MIN_SPAN_MS) {
                archive.save(span!!)
                lastSaved = now
            }
        }
    }

    private fun closeSpan(at: Long) {
        val s = span ?: return
        span = null
        onEnded(s.scene)
        val closed = s.copy(endedAt = at.coerceAtLeast(s.startedAt))
        if (closed.durationMs >= MIN_SPAN_MS) archive.save(closed)
    }

    private fun detect(): Scene? {
        val configs = runCatching { audio.activePlaybackConfigurations }.getOrDefault(emptyList())
        val usages = configs.map { it.audioAttributes }
        val mode = audio.mode
        val calling = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_CALL_SCREENING ||
            usages.any { it.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION } ||
            (mode == AudioManager.MODE_IN_COMMUNICATION && !ownCommunication)
        val session = playingSession()

        if (calling) {
            val app = session?.takeIf { it.first == SceneKind.CALL }?.second
            return Scene(SceneKind.CALL, app = app)
        }
        if (session != null) return Scene(session.first, session.second, session.third)
        if (!audio.isMusicActive) return null
        val media = usages.filter { it.usage == AudioAttributes.USAGE_MEDIA || it.usage == AudioAttributes.USAGE_GAME || it.usage == AudioAttributes.USAGE_UNKNOWN }
        val kind = when {
            media.any { it.contentType == AudioAttributes.CONTENT_TYPE_MUSIC } -> SceneKind.MUSIC
            media.any { it.contentType == AudioAttributes.CONTENT_TYPE_SPEECH } -> SceneKind.PODCAST
            media.any { it.contentType == AudioAttributes.CONTENT_TYPE_MOVIE } -> SceneKind.VIDEO
            else -> SceneKind.MEDIA
        }
        return Scene(kind)
    }

    /** 正在播放的媒体会话：类别、App 名、标题。需要用户开启通知使用权，否则返回 null。 */
    private fun playingSession(): Triple<SceneKind, String?, String?>? {
        if (!mediaAccessGranted(context)) return null
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val controllers = runCatching {
            manager.getActiveSessions(ComponentName(context, MediaListener::class.java))
        }.getOrDefault(emptyList())
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return null
        val known = KNOWN[playing.packageName]
        val meta = playing.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val label = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title?.takeIf { it.isNotBlank() }?.let { "《$it》" })
            .joinToString(" ").ifBlank { null }
        val kind = known?.first ?: SceneKind.MEDIA
        val app = known?.second ?: runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(playing.packageName, 0)).toString()
        }.getOrNull()
        return Triple(kind, app, label)
    }
}
