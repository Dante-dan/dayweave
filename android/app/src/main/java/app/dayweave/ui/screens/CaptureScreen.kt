@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.dayweave.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.dayweave.capture.CaptureService
import app.dayweave.capture.isBluetooth
import app.dayweave.capture.mediaAccessGranted
import app.dayweave.core.ServiceMode
import app.dayweave.ui.components.SceneChip
import app.dayweave.ui.motion.bloomTap
import app.dayweave.capture.CaptureState
import app.dayweave.capture.InputOption
import app.dayweave.capture.listInputs
import app.dayweave.graph
import app.dayweave.ui.Actions
import app.dayweave.ui.Navigator
import app.dayweave.ui.TabBarClearance
import app.dayweave.ui.components.Avatar
import app.dayweave.ui.components.CaptureOrb
import app.dayweave.ui.components.LiveDot
import app.dayweave.ui.components.glass
import app.dayweave.ui.components.pressable
import app.dayweave.ui.components.stopwatch
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import kotlinx.coroutines.delay
import app.dayweave.core.tr
import app.dayweave.core.deviceLabel

private const val METER_BARS = 44

@Composable
fun CaptureScreen(nav: Navigator, actions: Actions) {
    val context = LocalContext.current
    val graph = context.graph
    val capture by CaptureService.state.collectAsStateWithLifecycle()
    val input by graph.settings.input.collectAsStateWithLifecycle()
    val autoBackground by graph.settings.autoBackground.collectAsStateWithLifecycle()
    val mode by graph.settings.mode.collectAsStateWithLifecycle()
    val enrolled by graph.voice.ownerEnrolled.collectAsStateWithLifecycle()
    val people by graph.voice.people.collectAsStateWithLifecycle()
    val segments by graph.archive.segments.collectAsStateWithLifecycle()
    var showInputs by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    // 从系统设置页回来时重新检查通知使用权。
    var mediaAccess by remember { mutableStateOf(mediaAccessGranted(context)) }
    LifecycleResumeEffect(Unit) {
        mediaAccess = mediaAccessGranted(context)
        onPauseOrDispose {}
    }
    val monthSpeech = remember(segments) {
        val month = java.time.YearMonth.now()
        segments.filter { java.time.YearMonth.from(app.dayweave.ui.components.localDate(it.startedAt)) == month }.sumOf { it.speechMs }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(start = 4.dp, top = 14.dp)) {
            Text(tr("采集", "Capture"), style = Type.Display)
            Text(tr("安静地旁听：不占用耳机和通话，只转写人声", "A quiet listener: never takes over your earbuds or calls, transcribes only voices"), style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
        }

        // 记录状态
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp)).padding(vertical = 22.dp, horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CaptureOrb(
                running = capture.listening,
                levelDb = capture.micLevel,
                speaking = capture.micSpeaking,
                onClick = actions::toggleCapture,
                size = 128.dp,
            )
            Status(capture)
            AnimatedVisibility(capture.scene != null && capture.running, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                capture.scene?.let { SceneChip(it, Modifier.padding(top = 12.dp)) }
            }
            AnimatedVisibility(capture.running, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Meter(if (capture.micSpeaking) tr("人声", "Voice") else tr("环境", "Room"), capture.micLevel, capture.listening)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton(
                            if (capture.paused) tr("继续", "Resume") else tr("暂停", "Pause"),
                            if (capture.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        ) { if (capture.paused) CaptureService.resume(context) else CaptureService.pause(context) }
                        SoftButton(tr("结束", "Stop"), Icons.Outlined.Stop) { CaptureService.stop(context) }
                    }
                }
            }
            AnimatedVisibility(capture.notice != null && capture.listening) {
                Text(capture.notice.orEmpty(), style = Type.Micro, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            }
        }

        Group(tr("输入", "Input")) {
            val bluetooth = input?.isBluetooth == true
            Item(
                icon = if (bluetooth) Icons.Outlined.Headphones else Icons.Outlined.MicNone,
                title = tr("麦克风", "Microphone"),
                subtitle = when {
                    input == null -> tr("手机麦克风 · 不影响耳机里的音乐和通话", "Phone mic · leaves your music and calls alone")
                    bluetooth -> tr("${input?.name} · 有其他声音时自动让出，改用手机麦克风", "${input?.name} · steps aside for other audio and uses the phone mic")
                    else -> tr("${deviceLabel(input!!.name)} · 不影响耳机里的音乐和通话", "${deviceLabel(input!!.name)} · leaves your music and calls alone")
                },
                trailing = { Chevron() },
                onClick = { actions.requestMicPermission { showInputs = true } },
                enabled = !capture.running,
            )
            Divider()
            Item(
                icon = Icons.Outlined.LibraryMusic,
                title = tr("识别正在播放的内容", "Recognize what's playing"),
                subtitle = if (mediaAccess) tr("已开启：时间线会写明 App 和曲目，例如“听音乐 · 汽水音乐”", "On: the timeline names the app and track, e.g. “Music · Spotify”")
                else tr("可选。只读“正在播放”的信息，不读通知内容；不开启也能分出音乐、播客和通话", "Optional. Reads only “now playing”, never your notifications. Music, podcasts and calls are told apart even without it"),
                trailing = { if (mediaAccess) Check() else Chevron() },
                onClick = actions::openMediaAccess,
            )
        }

        Group(tr("通话", "Calls")) {
            val callFolder by graph.callRecordings.folder.collectAsStateWithLifecycle()
            val callCount by graph.callRecordings.imported.collectAsStateWithLifecycle()
            Item(
                icon = Icons.Outlined.Call,
                title = tr("自动导入通话录音", "Import call recordings"),
                subtitle = if (callFolder != null) tr("已开启 · 已导入 $callCount 段 · 原录音文件不会被改动或上传", "On · $callCount imported · the original files are never changed or uploaded")
                else tr("通话时拾日听不到对方。打开系统的通话录音，再授权拾日读取录音文件夹，挂断后自动转写", "During calls Dayweave can't hear the other side. Turn on the system call recorder, then let Dayweave read that folder; calls are transcribed after you hang up"),
                trailing = {
                    GlassSwitch(callFolder != null, enabled = true) { on ->
                        if (on) actions.pickCallRecordings() else graph.callRecordings.disable()
                    }
                },
                onClick = { if (callFolder == null) actions.pickCallRecordings() },
            )
        }

        Group(tr("无感使用", "Hands-free")) {
            Item(
                icon = Icons.Outlined.Home,
                title = tr("开始后回到桌面", "Go home after starting"),
                subtitle = tr("麦克风就绪后自动退到后台，在通知栏暂停或结束", "Moves to the background once the mic is ready. Pause or stop from the notification"),
                trailing = { GlassSwitch(autoBackground, enabled = true, onChange = graph.settings::setAutoBackground) },
                onClick = { graph.settings.setAutoBackground(!autoBackground) },
            )
            Divider()
            val standby by graph.settings.standby.collectAsStateWithLifecycle()
            Item(
                icon = Icons.Outlined.Notifications,
                title = tr("通知栏常驻开关", "Notification controls"),
                subtitle = tr("不在记录时也留一个“开始”按钮，下拉就能开始", "Keep a Start button in the notification shade even when idle"),
                trailing = {
                    GlassSwitch(standby, enabled = true) {
                        graph.settings.setStandby(it)
                        app.dayweave.capture.showStandby(context)
                    }
                },
                onClick = {
                    graph.settings.setStandby(!standby)
                    app.dayweave.capture.showStandby(context)
                },
            )
            Divider()
            Item(
                icon = Icons.Outlined.ToggleOn,
                title = tr("添加到控制中心", "Add to Control Center"),
                subtitle = tr("下拉控制中心，一下开始、暂停或继续", "Start, pause or resume from Control Center"),
                trailing = { Chevron() },
                onClick = { requestTile(context) },
            )
        }

        Group(tr("外观", "Appearance")) {
            val theme by graph.settings.theme.collectAsStateWithLifecycle()
            val language by graph.settings.language.collectAsStateWithLifecycle()
            Choices(app.dayweave.ui.theme.ThemeMode.entries, theme, { it.label }) { graph.settings.setTheme(it) }
            Divider()
            Choices(app.dayweave.core.Language.entries, language, { it.label }) {
                graph.settings.setLanguage(it)
                graph.voice.relabel()
            }
        }

        Group(tr("主人声纹", "Your voice")) {
            Item(
                icon = Icons.Outlined.Fingerprint,
                title = if (enrolled) tr("已认识你的声音", "Dayweave knows your voice") else tr("录入你的声音", "Teach Dayweave your voice"),
                subtitle = if (enrolled) tr("重新录入可以提高识别准确度", "Record again to improve accuracy") else tr("朗读 6 段短文，约 1 分钟；声纹只保存在本机", "Read 6 short lines, about a minute. Your voiceprint stays on this phone"),
                trailing = { if (enrolled) Check() else Chevron() },
                onClick = nav.openEnroll,
            )
            if (people.size > 1) {
                Divider()
                FlowRow(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    people.values.forEach { p ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(p, 46.dp)
                            Text(p.name, style = Type.Micro.copy(color = Ink.Primary), modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        Group(tr("服务", "Service")) {
            Item(
                icon = if (mode == ServiceMode.CLOUD) Icons.Outlined.Cloud else Icons.Outlined.Key,
                title = if (mode == ServiceMode.CLOUD) tr("拾日云", "Dayweave Cloud") else tr("自带密钥", "Your own keys"),
                subtitle = tr("本月有效转录 ${app.dayweave.ui.components.minutes(monthSpeech)} · 静音、环境音和播放的声音不计入", "${app.dayweave.ui.components.minutes(monthSpeech)} of voice this month · silence, room noise and playback don't count"),
                trailing = { Chevron() },
                onClick = nav.openService,
            )
        }

        Group(tr("数据", "Data")) {
            Item(
                icon = Icons.Outlined.DeleteSweep,
                title = tr("删除全部档案", "Delete all records"),
                subtitle = tr("文字、场景记录、整理和匿名说话人都会清除，声纹保留", "Removes transcripts, scenes, digests and unnamed speakers. Your voiceprint is kept"),
                trailing = {}, onClick = { confirmWipe = true }, danger = true,
            )
        }

        Text(
            tr("Dayweave · 拾日 0.2\n记录应在参与者知情的场景中进行", "Dayweave 0.2\nOnly record where everyone knows and agrees"),
            style = Type.Micro, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = TabBarClearance),
        )
    }

    if (showInputs) InputSheet { showInputs = false }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(tr("删除全部档案？", "Delete all records?"), style = Type.Title) },
            text = { Text(tr("这个操作不能撤销。", "This can't be undone."), style = Type.Body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    graph.archive.deleteAll()
                    graph.voice.forgetPeople()
                    graph.digest.clear()
                }) { Text(tr("删除", "Delete"), color = Ink.Danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text(tr("取消", "Cancel"), color = Ink.Primary) } },
            containerColor = Ink.Surface,
        )
    }
}

/** 一排等宽的选项：选中的是实心墨色，切换时颜色渐变，轻触有花瓣反馈。 */
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { m ->
            val on = m == selected
            val bg by animateColorAsState(if (on) Ink.Primary else Glass.Veil.copy(alpha = 0.6f), label = "bg")
            val fg by animateColorAsState(if (on) Ink.OnPrimary else Ink.Primary, label = "fg")
            Text(
                label(m),
                style = Type.Caption.copy(color = fg, fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg)
                    .bloomTap({ onSelect(m) }, scaleTo = 0.95f).padding(vertical = 11.dp),
            )
        }
    }
}

/** 请求系统把“拾日”快捷开关加到控制中心（Android 13+ 会弹出系统确认）。 */
private fun requestTile(context: android.content.Context) {
    val manager = context.getSystemService(android.app.StatusBarManager::class.java)
    manager.requestAddTileService(
        android.content.ComponentName(context, app.dayweave.capture.CaptureTile::class.java),
        tr("拾日", "Dayweave"),
        android.graphics.drawable.Icon.createWithResource(context, app.dayweave.R.drawable.ic_stat_listen),
        context.mainExecutor,
    ) {}
}

@Composable
private fun SoftButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Glass.Veil.copy(alpha = 0.85f)).pressable(onClick, scaleTo = 0.94f)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Ink.Primary, modifier = Modifier.size(16.dp))
        Text(text, style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun Status(capture: CaptureState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(capture.running) {
        while (capture.running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val phase = when {
        !capture.running -> 0
        capture.paused -> 1
        else -> 2
    }
    AnimatedContent(
        targetState = phase,
        transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
        label = "status",
    ) { p ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (p) {
                2 -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiveDot()
                        Text(
                            when {
                                !capture.micReady -> tr("正在连接麦克风…", "Connecting to the mic…")
                                capture.silenced -> tr("麦克风暂时让给了别的 App", "Another app is using the mic")
                                else -> tr("正在安静地聆听", "Quietly listening")
                            },
                            style = Type.Title,
                        )
                    }
                    Text(
                        "${stopwatch(now - capture.startedAt)} · ${capture.micName?.let(::deviceLabel).orEmpty()}",
                        style = Type.Caption.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                1 -> {
                    Text(tr("已暂停", "Paused"), style = Type.Title)
                    Text(tr("麦克风已释放，随时可以继续", "The mic is released. Resume any time"), style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
                }
                else -> {
                    Text(tr("轻触开始记录", "Tap to start"), style = Type.Title)
                    Text(tr("说过的话，会被安静地拾起", "What you say, gently gathered"), style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** 滚动的电平条：最近约 3.5 秒的电平，从右往左流动。 */
@Composable
private fun Meter(label: String, levelDb: Double, active: Boolean) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(METER_BARS) { add(0f) } } }
    val level = ((levelDb + 60) / 40).toFloat().coerceIn(0f, 1f)
    val current by animateFloatAsState(if (active) level else 0f, tween(80), label = "meter")
    LaunchedEffect(active) {
        while (true) {
            history.removeAt(0)
            history.add(current)
            delay(80)
        }
    }
    val brush = Brush.verticalGradient(listOf(Ink.Pink, Ink.Orange))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Micro.copy(color = Ink.Secondary), modifier = Modifier.width(40.dp))
        Canvas(Modifier.weight(1f).height(30.dp)) {
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (METER_BARS - 1)) / METER_BARS
            history.forEachIndexed { i, v ->
                val h = (size.height * (0.08f + v * 0.92f))
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2),
                    alpha = 0.35f + 0.65f * (i.toFloat() / METER_BARS),
                )
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = Type.Micro.copy(color = Ink.Secondary), modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, top = 6.dp))
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), elevation = 6.dp)) { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(thickness = 0.5.dp, color = Glass.Veil.copy(alpha = 0.9f), modifier = Modifier.padding(start = 58.dp))

@Composable
private fun Chevron() = Icon(Icons.Outlined.ChevronRight, null, tint = Ink.Tertiary)

@Composable
private fun Check() = Box(Modifier.size(22.dp).clip(CircleShape).background(Ink.StoryBrush), contentAlignment = Alignment.Center) {
    Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
}

@Composable
private fun GlassSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked,
    onCheckedChange = onChange,
    enabled = enabled,
    colors = SwitchDefaults.colors(
        checkedTrackColor = Ink.Primary, checkedThumbColor = Ink.OnPrimary,
        uncheckedTrackColor = Glass.Veil.copy(alpha = 0.7f), uncheckedBorderColor = Glass.Veil, uncheckedThumbColor = Ink.Tertiary,
    ),
)

@Composable
private fun Item(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick, enabled = enabled, scaleTo = 0.985f).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Glass.Veil.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (danger) Ink.Danger else Ink.Primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Headline.copy(color = if (danger) Ink.Danger else if (enabled) Ink.Primary else Ink.Tertiary))
            Text(subtitle, style = Type.Caption)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** 选择麦克风：耳机、车载蓝牙、手机麦克风同时在线时由用户决定。 */
@Composable
private fun InputSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = context.graph.settings
    val selected by settings.input.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf(listInputs(context)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Glass.Base,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("选择麦克风", "Choose a microphone"), style = Type.Title, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Refresh, tr("刷新", "Refresh"), modifier = Modifier.pressable({ options = listInputs(context) }).padding(6.dp))
            }
            Text(
                "手机麦克风最安静，完全不影响耳机。蓝牙耳机的麦克风要借用通话通道，所以拾日只在耳机空闲时借用，" +
                    "一有音乐、播客或通话就还回去，临时改用手机麦克风。",
                style = Type.Caption, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 16.dp).glass(RoundedCornerShape(24.dp), elevation = 4.dp)) {
                options.forEach { option -> InputRow(option, option.selection == selected) { settings.setInput(option.selection); onDismiss() } }
                if (options.isEmpty()) Text(tr("没有找到可用的麦克风", "No microphone found"), style = Type.Caption, modifier = Modifier.padding(20.dp))
            }
        }
    }
}

@Composable
private fun InputRow(option: InputOption, selected: Boolean, onClick: () -> Unit) {
    val ring by animateColorAsState(if (selected) Ink.Primary else Ink.Hairline, label = "ring")
    Row(
        Modifier.fillMaxWidth().pressable(onClick, scaleTo = 0.98f).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Glass.Veil.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
            Icon(if (option.isBluetooth) Icons.Outlined.Headphones else Icons.Outlined.MicNone, null)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(option.selection.name, style = Type.Headline)
            Text(if (option.isBluetooth) tr("空闲时借用 · 收音更近", "Borrowed when idle · closer pickup") else tr("推荐 · 静默旁听", "Recommended · never in the way"), style = Type.Caption)
        }
        Box(Modifier.size(24.dp).border(if (selected) 7.dp else 1.5.dp, ring, CircleShape))
    }
}
