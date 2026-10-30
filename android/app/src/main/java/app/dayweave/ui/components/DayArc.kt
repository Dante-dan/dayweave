package app.dayweave.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.dayweave.core.SceneKind
import app.dayweave.core.Segment
import app.dayweave.core.SourceKind
import app.dayweave.core.tr
import app.dayweave.ui.motion.Hue
import app.dayweave.ui.motion.TAU
import app.dayweave.ui.motion.petal
import app.dayweave.ui.motion.rememberReduceMotion
import app.dayweave.ui.motion.springStep
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 花轨从早上 5 点开始，到午夜结束。 */
private const val DAY_START_MIN = 5 * 60f
private const val DAY_SPAN_MIN = 19 * 60f
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun dayFraction(epochMs: Long): Float {
    val t = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return ((t.hour * 60 + t.minute - DAY_START_MIN) / DAY_SPAN_MIN).coerceIn(0f, 1f)
}

/**
 * 今日花轨（朝花夕拾）：一天是一道日光走过的弧，y(t) = 底 − 高 · sin(πt)。
 * 太阳停在“此刻”的位置，已经走过的一段用晨光到暮色的颜色画出，未到的部分是虚线。
 * 起点是日出，终点是新月，两端点缀花瓣。今天的每一段对话是一粒光，落在它发生的时刻；轻触光粒打开那段对话。
 * 新的光粒出现时，按欠阻尼弹簧“噗”地亮起。
 */
@Composable
fun DayArc(today: List<Segment>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val draw = remember { Animatable(if (reduce) 1f else 0f) }
    val appear = remember { mutableStateMapOf<String, Animatable<Float, *>>() }
    val pulse by rememberInfiniteTransition(label = "sun").animateFloat(0f, 1f, infiniteRepeatable(tween(3_200, easing = LinearEasing)), label = "pulse")
    val now = dayFraction(System.currentTimeMillis())

    LaunchedEffect(Unit) { draw.animateTo(1f, tween(1_100, easing = Emphasized)) }
    LaunchedEffect(today.map { it.id }) {
        today.sortedBy { it.startedAt }.forEachIndexed { i, s ->
            if (s.id !in appear) {
                val a = Animatable(if (reduce) 1f else 0f)
                appear[s.id] = a
                launch {
                    delay(if (draw.value < 1f) 300L + i * 70L else 0L)
                    a.animateTo(1f, tween(1_000, easing = LinearEasing))
                }
            }
        }
    }

    val total = today.sumOf { it.durationMs }
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(92.dp).pointerInput(today) {
                detectTapGestures { tap ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    today.minByOrNull { s -> point(dayFraction(s.startedAt), w, h).let { hypot(it.x - tap.x, it.y - tap.y) } }
                        ?.takeIf { s -> point(dayFraction(s.startedAt), w, h).let { hypot(it.x - tap.x, it.y - tap.y) } < 28.dp.toPx() }
                        ?.let { onOpen(it.id) }
                }
            },
        ) {
            val w = size.width
            val h = size.height
            val progress = draw.value
            // 整天的弧：虚线
            val full = Path()
            val passed = Path()
            val steps = 64
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val p = point(t, w, h)
                if (i == 0) full.moveTo(p.x, p.y) else full.lineTo(p.x, p.y)
            }
            drawPath(full, Ink.Tertiary.copy(alpha = 0.45f * progress), style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))))
            // 走过的部分：从晨光到暮色，随入场动画伸长
            val reach = now * progress
            val n = (steps * reach).toInt().coerceAtLeast(1)
            for (i in 0..n) {
                val p = point(reach * i / n, w, h)
                if (i == 0) passed.moveTo(p.x, p.y) else passed.lineTo(p.x, p.y)
            }
            drawPath(
                passed,
                Brush.horizontalGradient(listOf(Hue.Peach, Hue.Rose, Hue.Lilac), startX = point(0f, w, h).x, endX = point(1f, w, h).x),
                style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round),
            )
            // 起点：地平线上升起的半个太阳；终点：一弯新月。两端各点缀几片花瓣（朝花、夕拾）。
            val dawn = point(0f, w, h)
            val dusk = point(1f, w, h)
            sunrise(dawn, progress, pulse)
            moon(dusk, progress)
            accentPetals(dawn, dusk, progress, pulse)
            // 此刻：一团暖白的光，呼吸的光晕 r = r₀(1 + 0.12·sin 2πφ)
            val sun = point(reach, w, h)
            val glow = 13.dp.toPx() * (1 + 0.12f * sin(TAU * pulse))
            drawCircle(Brush.radialGradient(listOf(Hue.Gold.copy(alpha = 0.4f * progress), Color.Transparent), center = sun, radius = glow), glow, sun)
            drawCircle(Color.White.copy(alpha = 0.95f * progress), 3.2.dp.toPx() * progress, sun)
            // 今天的每段对话是一粒光：时长越长越大（r ∝ √分钟），颜色取它所在时刻的天色；
            // 同一时段的光粒按向日葵叶序（r = c·√k，θ = k·137.5°）聚成一小团光尘。闪烁：α = 0.78 + 0.22·sin(2π(1.5φ + 种子))。
            var anchor = Offset.Unspecified
            var stack = 0
            for ((index, s) in today.sortedBy { it.startedAt }.withIndex()) {
                val a = appear[s.id]?.value ?: continue
                val t = dayFraction(s.startedAt)
                val base = point(t, w, h)
                if (anchor != Offset.Unspecified && base.x - anchor.x < 14.dp.toPx()) stack++ else {
                    anchor = base
                    stack = 0
                }
                if (a <= 0f) continue
                val golden = stack * 2.39996f
                val p = anchor + Offset(cos(golden), sin(golden)) * (4.6.dp.toPx() * kotlin.math.sqrt(stack.toFloat()))
                val pop = springStep(a, response = 0.45f, damping = 0.5f).coerceAtLeast(0f)
                val minutes = (s.durationMs / 60_000f).coerceIn(0.1f, 9f)
                val r = (1.8.dp.toPx() + 1.4.dp.toPx() * kotlin.math.sqrt(minutes)) * pop
                val twinkle = 0.78f + 0.22f * sin(TAU * (1.5f * pulse + app.dayweave.ui.motion.seed(index, 31)))
                val color = if (s.source == SourceKind.CALL || s.scene?.kind == SceneKind.CALL) Hue.Mint else skyAt(t)
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.5f * twinkle), Color.Transparent), center = p, radius = r * 3.2f + 1f), r * 3.2f, p)
                drawCircle(color, r, p, alpha = twinkle)
                drawCircle(Color.White, r * 0.42f, p - Offset(r * 0.25f, r * 0.25f), alpha = 0.8f * twinkle)
            }
        }
        Text(
            if (today.isEmpty()) tr("今天还没有拾起什么", "Nothing gathered yet today")
            else tr("今天拾起 ${today.size} 段 · ${minutes(total)}", "Gathered ${today.size} today · ${minutes(total)}"),
            style = Type.Caption,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun point(t: Float, w: Float, h: Float): Offset {
    val pad = w * 0.06f
    val top = h * 0.16f
    val base = h * 0.82f
    return Offset(pad + t * (w - 2 * pad), base - (base - top) * sin(PI.toFloat() * t))
}

/** 弧在 t 处的切线角（度）：dy/dx = −(base − top)·π·cos(πt) / (w − 2·pad)。 */
private fun slope(t: Float, w: Float, h: Float): Float {
    val pad = w * 0.06f
    val dx = w - 2 * pad
    val dy = -(h * 0.82f - h * 0.16f) * PI.toFloat() * cos(PI.toFloat() * t)
    return atan2(dy, dx) * 180f / PI.toFloat()
}

/** 天色：晨光的蜜桃 → 正午的玫瑰 → 暮色的淡紫，按 t 线性插值。 */
private fun skyAt(t: Float): Color {
    val stops = listOf(Hue.Peach, Hue.Rose, Hue.Lilac)
    val x = t.coerceIn(0f, 1f) * (stops.size - 1)
    val i = x.toInt().coerceAtMost(stops.size - 2)
    return androidx.compose.ui.graphics.lerp(stops[i], stops[i + 1], x - i)
}

/** 日出：地平线上的半个太阳，光芒长度随 φ 呼吸 L = L₀(1 + 0.18·sin 2πφ)。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.sunrise(at: Offset, progress: Float, pulse: Float) {
    val r = 7.dp.toPx() * progress
    val horizon = 1.4.dp.toPx()
    drawLine(Ink.Tertiary.copy(alpha = 0.6f * progress), at + Offset(-14.dp.toPx(), 0f), at + Offset(14.dp.toPx(), 0f), horizon, StrokeCap.Round)
    drawArc(Hue.Gold, 180f, 180f, true, topLeft = at - Offset(r, r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r), alpha = progress)
    val len = 4.dp.toPx() * (1 + 0.18f * sin(TAU * pulse)) * progress
    for (k in 0 until 5) {
        val ang = PI.toFloat() * (1 + (k + 0.5f) / 5f)
        val dir = Offset(cos(ang), sin(ang))
        drawLine(Hue.Gold.copy(alpha = 0.8f * progress), at + dir * (r + 2.dp.toPx()), at + dir * (r + 2.dp.toPx() + len), 1.6.dp.toPx(), StrokeCap.Round)
    }
}

/** 新月：两个圆相减（月亮减去偏移的一个圆），开口朝向右上。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.moon(at: Offset, progress: Float) {
    val r = 7.dp.toPx() * progress
    if (r <= 0.5f) return
    val c = at - Offset(0f, r * 0.9f)
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.55f, -r * 0.35f), r * 0.86f)) }
    val crescent = Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, disc, bite)
    drawCircle(Brush.radialGradient(listOf(Hue.Lilac.copy(alpha = 0.35f * progress), Color.Transparent), center = c, radius = r * 2.6f), r * 2.6f, c)
    drawPath(crescent, Hue.Lilac, alpha = progress)
}

/** 两端的花瓣：日出旁两片、新月旁两片，缓缓摇摆 θ = θ₀ + 14°·sin(2πφ + i)。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.accentPetals(dawn: Offset, dusk: Offset, progress: Float, pulse: Float) {
    val u = 1.dp.toPx()
    val spots = listOf(
        Triple(dawn + Offset(-12 * u, -18 * u), 8f * u, -30f),
        Triple(dawn + Offset(14 * u, -24 * u), 6f * u, 40f),
        Triple(dusk + Offset(-16 * u, -26 * u), 6.5f * u, 150f),
        Triple(dusk + Offset(12 * u, 6 * u), 7.5f * u, 210f),
    )
    spots.forEachIndexed { i, (p, size, angle) ->
        val sway = 14f * sin(TAU * pulse + i)
        val bob = Offset(0f, 1.5f * u * sin(TAU * pulse + i * 1.3f))
        petal(p + bob, size * progress, angle + sway, if (i % 2 == 0) Hue.Blush else Hue.Rose, alpha = 0.85f * progress)
    }
}
