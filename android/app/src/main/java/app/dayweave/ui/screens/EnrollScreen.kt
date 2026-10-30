package app.dayweave.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dayweave.capture.CaptureService
import app.dayweave.core.SAMPLE_RATE
import app.dayweave.graph
import app.dayweave.ui.Actions
import app.dayweave.ui.Navigator
import app.dayweave.ui.components.pressable
import app.dayweave.ui.motion.PetalBurst
import app.dayweave.ui.motion.Petals
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import app.dayweave.voice.EnrollRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.dayweave.core.tr

private data class Prompt(val lang: String, val text: String)

/** 一些温柔的句子：中文、英文、中英混说各有覆盖，让声纹不偏向某一种语言，读起来也是一种小小的享受。 */
private val PROMPTS_ZH = listOf(
    Prompt("中文", "我想把日子过成一首慢慢写的诗，不急着押韵，只认真写好每一行。"),
    Prompt("English", "Every morning is a quiet invitation to begin again, a little braver than yesterday."),
    Prompt("中文", "小时候外婆常说，路是走出来的，不是想出来的。后来我才慢慢懂了。"),
    Prompt("English", "The stars never rush, and yet they always arrive exactly where they need to be."),
    Prompt("中英", "那天在海边，我对自己说 just keep going，风会把答案带过来。"),
    Prompt("中文", "愿你走过的每一段路都有光，遇见的每一个人都温柔。"),
)

/** 英文界面的用户读英文短句；声纹和语言无关，读自己最常说的语言效果最好。 */
private val PROMPTS_EN = listOf(
    Prompt("1", "Every morning is a quiet invitation to begin again, a little braver than yesterday."),
    Prompt("2", "I want to live my days like a slow poem, not rushing the rhyme, just writing each line with care."),
    Prompt("3", "The stars never rush, and yet they always arrive exactly where they need to be."),
    Prompt("4", "My grandmother used to say the road is made by walking, not by thinking about it."),
    Prompt("5", "That day by the sea, I told myself to keep going, and let the wind bring the answers."),
    Prompt("6", "May every road you walk have light, and every person you meet be kind."),
)

private val PROMPTS get() = if (app.dayweave.core.Lang.english) PROMPTS_EN else PROMPTS_ZH
private const val MIN_CLIP_MS = 2_500L
private const val MAX_CLIP_MS = 15_000L

private enum class Phase { INTRO, READING, SAVING, DONE, FAILED }

@Composable
fun EnrollScreen(nav: Navigator, actions: Actions) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val clips = remember { mutableStateListOf<ShortArray>() }
    var phase by remember { mutableStateOf(Phase.INTRO) }
    var index by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableDoubleStateOf(-90.0) }
    var hint by remember { mutableStateOf<String?>(null) }
    val recorder = remember { EnrollRecorder(context, graph.settings.input.value) }
    val captureRunning = CaptureService.state.value.running

    DisposableEffect(Unit) { onDispose { if (recording) recorder.stop() } }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = System.currentTimeMillis() - startedAt
            level = recorder.levelDb
            if (elapsed >= MAX_CLIP_MS) break
            delay(40)
        }
    }

    fun finishClip() {
        val pcm = recorder.stop()
        recording = false
        if (pcm.size < SAMPLE_RATE * MIN_CLIP_MS / 1000) {
            hint = tr("再读完整一点", "Read a little more of it")
            return
        }
        hint = null
        clips += pcm
        if (index < PROMPTS.lastIndex) {
            index++
        } else {
            phase = Phase.SAVING
            scope.launch {
                val ok = withContext(Dispatchers.Default) { graph.voice.enroll(clips.toList()) }
                phase = if (ok) Phase.DONE else Phase.FAILED
            }
        }
    }

    LaunchedEffect(elapsed >= MAX_CLIP_MS && recording) { if (recording && elapsed >= MAX_CLIP_MS) finishClip() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
    ) {
        // 快拍式分段进度条
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PROMPTS.indices.forEach { i ->
                val target = when {
                    phase == Phase.DONE || i < clips.size -> 1f
                    i == index && recording -> (elapsed.toFloat() / 6_000).coerceIn(0.05f, 0.95f)
                    else -> 0f
                }
                val fill by animateFloatAsState(target, tween(if (target == 1f) 350 else 120), label = "bar$i")
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Glass.Veil.copy(alpha = 0.8f))) {
                    Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Ink.StoryBrush))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (phase) {
                    Phase.INTRO -> tr("声纹录入", "Your voice")
                    Phase.READING -> "${index + 1} / ${PROMPTS.size}"
                    else -> tr("声纹录入", "Your voice")
                },
                style = Type.Headline,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.Close, tr("关闭", "Close"), modifier = Modifier.size(28.dp).pressable(nav.back, scaleTo = 0.8f))
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = phase to index,
                transitionSpec = {
                    if (initialState.first == Phase.READING && targetState.first == Phase.READING) {
                        (slideInHorizontally(tween(380)) { it / 3 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(320)) { -it / 3 } + fadeOut(tween(200)))
                    } else {
                        (fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.94f)) togetherWith fadeOut(tween(200))
                    }
                },
                label = "prompt",
            ) { (p, i) ->
                when (p) {
                    Phase.INTRO -> Intro(captureRunning)
                    Phase.READING -> PromptCard(PROMPTS[i])
                    Phase.SAVING -> Centered(tr("正在生成声纹…", "Creating your voiceprint…"), tr("在手机上完成，不会上传", "Done on this phone, never uploaded"))
                    Phase.DONE -> Done()
                    Phase.FAILED -> Centered(tr("没能生成声纹", "Couldn't create a voiceprint"), tr("录音太短或太安静，请换个安静的地方再试", "The recording was too short or too quiet. Try again somewhere calm"))
                }
            }
        }

        AnimatedVisibility(hint != null) {
            Text(hint.orEmpty(), style = Type.Caption.copy(color = Ink.Danger), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        }

        Box(Modifier.fillMaxWidth().padding(bottom = 28.dp), contentAlignment = Alignment.Center) {
            when (phase) {
                Phase.INTRO -> PrimaryButton(if (captureRunning) tr("先停止记录再录入", "Stop recording first") else tr("开始", "Start"), enabled = !captureRunning) {
                    actions.requestMicPermission { phase = Phase.READING }
                }
                Phase.READING -> RecordButton(recording, elapsed, level) {
                    if (recording) {
                        if (elapsed >= MIN_CLIP_MS) finishClip()
                    } else {
                        hint = null
                        recorder.start()
                        startedAt = System.currentTimeMillis()
                        elapsed = 0
                        recording = true
                    }
                }
                Phase.SAVING -> Unit
                Phase.DONE -> PrimaryButton(tr("完成", "Done")) { nav.back() }
                Phase.FAILED -> PrimaryButton(tr("重新开始", "Start over")) {
                    clips.clear()
                    index = 0
                    phase = Phase.READING
                }
            }
        }
    }
}

@Composable
private fun Intro(captureRunning: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Ink.StoryBrush), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MicNone, null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(tr("让拾日认出你", "Let Dayweave know you"), style = Type.Display, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            tr("接下来是 6 段温柔的短句，有中文也有英文。\n按下按钮，用平常说话的语气读出来，读完再按一次。", "Next come 6 gentle lines.\nTap the button, read each one in your usual voice, then tap again.") +
                if (captureRunning) tr("\n\n录入时需要先暂停记录。", "\n\nRecording needs to be stopped first.") else "",
            style = Type.Body.copy(color = Ink.Secondary),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PromptCard(prompt: Prompt) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            prompt.lang,
            style = Type.Micro.copy(color = Ink.OnPrimary, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.Primary).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(prompt.text, style = Type.Prompt, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Centered(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = Type.Title)
        Text(subtitle, style = Type.Caption, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Done() {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
        PetalBurst(pop.targetValue, Petals.BLOOM, Modifier.size(104.dp), count = 18, durationMs = 1_400)
        Box(
            Modifier.size(104.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape).background(Ink.StoryBrush),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(52.dp)) }
        }
        Spacer(Modifier.height(24.dp))
        Text(tr("声纹已录入", "Voice saved"), style = Type.Display)
        Text(tr("之后的对话里，你会出现在右侧", "From now on, you'll appear on the right"), style = Type.Caption, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = Type.Headline.copy(color = Ink.OnPrimary),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Ink.Primary else Ink.Tertiary)
            .pressable(onClick, enabled = enabled, scaleTo = 0.97f)
            .padding(vertical = 15.dp),
    )
}

/** 录音按钮：外圈渐变进度，内圈随音量缩放；未达到最短时长时进度为灰色。 */
@Composable
private fun RecordButton(recording: Boolean, elapsed: Long, level: Double, onClick: () -> Unit) {
    val levelScale by animateFloatAsState(
        if (recording) 0.92f + (((level + 60) / 40).toFloat().coerceIn(0f, 1f)) * 0.12f else 1f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "level",
    )
    val progress = (elapsed.toFloat() / MAX_CLIP_MS).coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(92.dp).pressable(onClick, scaleTo = 0.9f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                drawCircle(Ink.Fill, style = Stroke(stroke))
                if (recording) {
                    drawArc(
                        brush = if (elapsed >= MIN_CLIP_MS) Brush.sweepGradient(Ink.StorySweep) else Brush.linearGradient(listOf(Ink.Tertiary, Ink.Tertiary)),
                        startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                        topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                    )
                }
            }
            Box(
                Modifier.size(70.dp).graphicsLayer { scaleX = levelScale; scaleY = levelScale }.clip(CircleShape)
                    .background(if (recording) Ink.Live else Ink.Primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (recording) Icons.Outlined.Stop else Icons.Outlined.MicNone, if (recording) tr("读完了", "Done reading") else tr("开始朗读", "Start reading"), tint = if (recording) Color.White else Ink.OnPrimary, modifier = Modifier.size(32.dp))
            }
        }
        Text(if (recording) tr("读完后轻触", "Tap when you're done") else tr("轻触开始朗读", "Tap to start reading"), style = Type.Caption, modifier = Modifier.padding(top = 12.dp))
    }
}
