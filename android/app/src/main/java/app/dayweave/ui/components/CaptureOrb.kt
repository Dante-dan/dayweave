package app.dayweave.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.dayweave.ui.motion.PetalBurst
import app.dayweave.ui.motion.Petals
import app.dayweave.ui.motion.springImpulse
import app.dayweave.ui.theme.Ink

/**
 * 记录按钮，像一个快拍头像：
 * - 未记录：细灰圈；
 * - 记录中：渐变光环缓慢旋转，内圈随音量呼吸；
 * - 有人说话：向外扩散两圈涟漪。
 */
@Composable
fun CaptureOrb(
    running: Boolean,
    levelDb: Double,
    speaking: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 148.dp,
) {
    val t = rememberInfiniteTransition(label = "orb")
    val rotation by t.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "rot")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing)), label = "ripple")
    val breathe by t.animateFloat(0.985f, 1.015f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")

    val ringAlpha by animateFloatAsState(if (running) 1f else 0f, tween(600), label = "ring")
    val rippleAlpha by animateFloatAsState(if (running && speaking) 1f else 0f, tween(400), label = "rippleAlpha")
    // -60 dBFS 以下视为安静，-20 dBFS 为满幅。
    val level = ((levelDb + 60) / 40).toFloat().coerceIn(0f, 1f)
    val levelScale by animateFloatAsState(
        if (running) 1f + level * 0.08f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "level",
    )

    // 开启、关闭时的“一下”：用阻尼弹簧的冲击响应做挤压与拉伸（横向胀、纵向缩，体积大致守恒）。
    val kick = remember { Animatable(1f) }
    val firstRun = remember { mutableStateOf(true) }
    LaunchedEffect(running) {
        if (firstRun.value) {
            firstRun.value = false
            return@LaunchedEffect
        }
        kick.snapTo(0f)
        kick.animateTo(1f, tween(900, easing = LinearEasing))
    }
    val impulse = if (kick.value < 1f) springImpulse(kick.value * 0.9f) * (if (running) 0.09f else -0.06f) else 0f

    Box(modifier.size(size * 1.45f), contentAlignment = Alignment.Center) {
        // 开启时花瓣绽开，关闭时落花
        PetalBurst(running, if (running) Petals.BLOOM else Petals.FALL, Modifier.size(size), count = if (running) 16 else 10)
        // 涟漪
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = rippleAlpha }) {
            val base = size.toPx() / 2
            listOf(ripple, (ripple + 0.5f) % 1f).forEach { p ->
                drawCircle(
                    brush = Brush.radialGradient(listOf(Ink.Pink.copy(alpha = 0.18f * (1 - p)), Color.Transparent), radius = base * (1 + p * 0.45f) + 1),
                    radius = base * (1 + p * 0.45f),
                )
            }
        }

        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = breathe * levelScale * (1 + impulse)
                    scaleY = breathe * levelScale * (1 - impulse * 0.7f)
                }
                .pressable(onClick, scaleTo = 0.93f),
            contentAlignment = Alignment.Center,
        ) {
            // 底圈：未记录时的细灰线
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Ink.Hairline.copy(alpha = 1 - ringAlpha), style = Stroke(1.5.dp.toPx()))
            }
            // 旋转的渐变光环
            Canvas(Modifier.fillMaxSize().rotate(rotation).graphicsLayer { alpha = ringAlpha }) {
                val stroke = 4.5.dp.toPx()
                drawArc(
                    brush = Brush.sweepGradient(Ink.StorySweep, center = Offset(this.size.width / 2, this.size.height / 2)),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                )
            }
            // 内圈
            Canvas(Modifier.size(size - 22.dp)) {
                drawCircle(if (running) Ink.Primary else Ink.Surface)
                drawCircle(Ink.Fill.copy(alpha = if (running) 0f else 1f), style = Stroke(1.dp.toPx()))
            }
            AnimatedContent(
                targetState = running,
                transitionSpec = { (fadeIn(tween(300)) + scaleIn(initialScale = 0.7f)) togetherWith (fadeOut(tween(150)) + scaleOut(targetScale = 0.7f)) },
                label = "icon",
            ) { on ->
                Icon(
                    if (on) Icons.Outlined.GraphicEq else Icons.Outlined.MicNone,
                    contentDescription = if (on) "停止记录" else "开始记录",
                    tint = if (on) Ink.OnPrimary else Ink.Primary,
                    modifier = Modifier.size(size * 0.26f),
                )
            }
        }
    }
}
