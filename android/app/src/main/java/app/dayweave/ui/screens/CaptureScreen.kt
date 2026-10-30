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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SpeakerGroup
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
import app.dayweave.capture.CaptureService
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

private const val METER_BARS = 44

@Composable
fun CaptureScreen(nav: Navigator, actions: Actions) {
    val context = LocalContext.current
    val graph = context.graph
    val capture by CaptureService.state.collectAsStateWithLifecycle()
    val input by graph.settings.input.collectAsStateWithLifecycle()
    val playback by graph.settings.capturePlayback.collectAsStateWithLifecycle()
    val enrolled by graph.voice.ownerEnrolled.collectAsStateWithLifecycle()
    val people by graph.voice.people.collectAsStateWithLifecycle()
    var showInputs by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(start = 4.dp, top = 14.dp)) {
            Text("采集", style = Type.Display)
            Text("声音只为转写临时保留，文字存好后即删除", style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
        }

        // 记录状态
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp)).padding(vertical = 22.dp, horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CaptureOrb(
                running = capture.running,
                levelDb = maxOf(capture.micLevel, capture.playbackLevel),
                speaking = capture.micSpeaking || capture.playbackSpeaking,
                onClick = actions::toggleCapture,
                size = 128.dp,
            )
            Status(capture)
            AnimatedVisibility(capture.running, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Meter("麦克风", capture.micLevel, capture.running, playbackChannel = false)
                    if (playback) Meter(if (capture.playbackOn) "播放声音" else "播放声音 · 未授权", capture.playbackLevel, capture.playbackOn, playbackChannel = true)
                }
            }
            AnimatedVisibility(capture.notice != null && capture.running) {
                Text(capture.notice.orEmpty(), style = Type.Micro, modifier = Modifier.padding(top = 10.dp))
            }
        }

        Group("输入") {
            Item(
                icon = if (input?.name?.contains("手机") == true) Icons.Rounded.Mic else Icons.Rounded.Headphones,
                title = "麦克风",
                subtitle = input?.name ?: "自动选择第一个可用设备",
                trailing = { Chevron() },
                onClick = { actions.requestMicPermission { showInputs = true } },
                enabled = !capture.running,
            )
            Divider()
            Item(
                icon = Icons.Rounded.SpeakerGroup,
                title = "耳机 / 扬声器里的声音",
                subtitle = "一起记录视频、播客、网页会议。电话和多数会议 App 的会中声音系统不允许录制",
                trailing = { GlassSwitch(playback, enabled = !capture.running, onChange = graph.settings::setCapturePlayback) },
                onClick = { if (!capture.running) graph.settings.setCapturePlayback(!playback) },
            )
        }

        Group("主人声纹") {
            Item(
                icon = Icons.Rounded.Fingerprint,
                title = if (enrolled) "已认识你的声音" else "录入你的声音",
                subtitle = if (enrolled) "重新录入可以提高识别准确度" else "朗读中英文各 3 段，约 1 分钟；声纹只保存在本机",
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

        Group("服务") {
            val ready = graph.auth.available
            Item(
                icon = if (ready) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                title = "转写 · Google Chirp 3",
                subtitle = if (ready) "中英双语，自动区分说话人" else "未配置凭证：运行 android/scripts/push-credentials.sh",
                trailing = {}, onClick = {},
            )
            Divider()
            Item(
                icon = Icons.Rounded.AutoAwesome,
                title = "整理 · Gemini",
                subtitle = "每天自动生成总结、决定和要跟进的事",
                trailing = {}, onClick = {},
            )
        }

        Group("数据") {
            Item(
                icon = Icons.Rounded.DeleteSweep,
                title = "删除全部档案",
                subtitle = "文字、整理和匿名说话人都会清除，声纹保留",
                trailing = {}, onClick = { confirmWipe = true }, danger = true,
            )
        }

        Text(
            "Dayweave · 拾日 0.1 demo\n记录应在参与者知情的场景中进行",
            style = Type.Micro, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = TabBarClearance),
        )
    }

    if (showInputs) InputSheet { showInputs = false }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("删除全部档案？", style = Type.Title) },
            text = { Text("这个操作不能撤销。", style = Type.Body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    graph.archive.deleteAll()
                    graph.voice.forgetPeople()
                    graph.digest.clear()
                }) { Text("删除", color = Ink.Danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("取消", color = Ink.Primary) } },
            containerColor = Color.White,
        )
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
    AnimatedContent(
        targetState = capture.running,
        transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
        label = "status",
    ) { running ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveDot()
                    Text(if (capture.micReady) "正在聆听" else "正在连接麦克风…", style = Type.Title)
                }
                Text(
                    "${stopwatch(now - capture.startedAt)} · ${capture.micName ?: ""}",
                    style = Type.Caption.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text("轻触开始记录", style = Type.Title)
                Text("说过的话，会被安静地拾起", style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 滚动的电平条：最近约 3.5 秒的电平，从右往左流动。 */
@Composable
private fun Meter(label: String, levelDb: Double, active: Boolean, playbackChannel: Boolean) {
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
    val brush = if (playbackChannel) Brush.verticalGradient(listOf(Color(0xFF7CC4FA), Color(0xFFB9A7FF)))
    else Brush.verticalGradient(listOf(Ink.Pink, Ink.Orange))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Micro.copy(color = Ink.Secondary), modifier = Modifier.width(78.dp))
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
private fun Divider() = HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(start = 58.dp))

@Composable
private fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = Ink.Tertiary)

@Composable
private fun Check() = Box(Modifier.size(22.dp).clip(CircleShape).background(Ink.StoryBrush), contentAlignment = Alignment.Center) {
    Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
}

@Composable
private fun GlassSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked,
    onCheckedChange = onChange,
    enabled = enabled,
    colors = SwitchDefaults.colors(
        checkedTrackColor = Ink.Primary, checkedThumbColor = Color.White,
        uncheckedTrackColor = Color.White.copy(alpha = 0.7f), uncheckedBorderColor = Color.White, uncheckedThumbColor = Ink.Tertiary,
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
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
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
                Text("选择麦克风", style = Type.Title, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Refresh, "刷新", modifier = Modifier.pressable({ options = listInputs(context) }).padding(6.dp))
            }
            Text("断开后只会重连你选的设备，不会跳到其他蓝牙设备。", style = Type.Caption, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 16.dp).glass(RoundedCornerShape(24.dp), elevation = 4.dp)) {
                options.forEach { option -> InputRow(option, option.selection == selected) { settings.setInput(option.selection); onDismiss() } }
                if (options.isEmpty()) Text("没有找到可用的麦克风", style = Type.Caption, modifier = Modifier.padding(20.dp))
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
        Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
            Icon(if (option.isBluetooth) Icons.Rounded.Headphones else Icons.Rounded.Mic, null)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(option.selection.name, style = Type.Headline)
            Text(if (option.isBluetooth) "蓝牙通话麦克风" else "内置麦克风", style = Type.Caption)
        }
        Box(Modifier.size(24.dp).border(if (selected) 7.dp else 1.5.dp, ring, CircleShape))
    }
}
