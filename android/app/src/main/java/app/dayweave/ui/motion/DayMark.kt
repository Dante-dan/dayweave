package app.dayweave.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dayweave.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * 拾日的标志与底栏图标，和 App 图标（android/tools/icon.py）同一套几何：
 * 一个圆是“日”，上沿细是日光的轨迹，下沿厚是捧起的掌心；花开在左上的晨光处，花瓣落进右下的掌心。
 * 用平涂的几种颜色区分各部分，不用渐变。
 */

/** 多色图标的调色板：每个部分一种颜色，深浅色各一套。 */
object Hue {
    val Rose get() = if (Palette.dark) Color(0xFFFF8DB6) else Color(0xFFE34F86)
    val Blush get() = if (Palette.dark) Color(0xFFFFB3CB) else Color(0xFFF7A1BE)
    val Lilac get() = if (Palette.dark) Color(0xFFB4A8FF) else Color(0xFF8C7BFF)
    val Sky get() = if (Palette.dark) Color(0xFF8DCBFF) else Color(0xFF4FA8F0)
    val Peach get() = if (Palette.dark) Color(0xFFFFB08A) else Color(0xFFFF8A5B)
    val Gold get() = if (Palette.dark) Color(0xFFFFD36B) else Color(0xFFF2B02E)
    val Mint get() = if (Palette.dark) Color(0xFF7FE0C2) else Color(0xFF2EB98E)
}

/** 标志动画的一帧。全部为 1（静止的完成态）时就是静态标志。 */
class DayFrame(
    /** 日光弧画出的比例 0..1。 */
    val light: Float = 1f,
    /** 掌心出现的程度 0..1。 */
    val cradle: Float = 1f,
    /** 五片花瓣各自开放的程度（可以过冲）。 */
    val bloom: FloatArray = FloatArray(5) { 1f },
    /** 飘落的进度 0..1；为 null 表示花瓣已经躺在掌心里。 */
    val fall: Float? = null,
    /** 掌心被压下的位移（正值向下），接住花瓣时的回弹。 */
    val squash: Float = 0f,
    /** 花的微微摆动（度）。 */
    val sway: Float = 0f,
) {
    companion object {
        val Still = DayFrame()
    }
}

/** 画“日”：单位坐标 0..1 映射到 size。alpha 用于未选中时整体降不透明度。 */
fun DrawScope.drawDay(
    alpha: Float = 1f,
    light: Color = Hue.Lilac,
    cradle: Color = Hue.Rose,
    petal: Color = Hue.Blush,
    heart: Color = Hue.Gold,
    frame: DayFrame = DayFrame.Still,
) {
    val s = size.minDimension
    val c = Offset(size.width / 2, size.height * 0.52f)
    val r = s * 0.36f
    // 日光的轨迹：上半圆，细，按 frame.light 从左往右画出
    if (frame.light > 0f) {
        drawArc(light, 182f, 176f * frame.light, false, topLeft = c - Offset(r, r), size = Size(2 * r, 2 * r), alpha = alpha, style = Stroke(s * 0.065f, cap = StrokeCap.Round))
    }
    // 掌心：下半圆，中间厚两头尖的月牙；接住花瓣时整体下沉 squash 再弹回
    if (frame.cradle > 0f) {
        val dip = Offset(0f, frame.squash * s)
        val thick = frame.cradle
        val cradlePath = Path().apply {
            val n = 32
            for (i in 0..n) {
                val t = -0.02f + (PI.toFloat() + 0.04f) * i / n
                val rr = r + s * 0.05f * sin(t) * thick
                val p = c + dip * sin(t) + Offset(cos(t), sin(t)) * rr
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            for (i in n downTo 0) {
                val t = -0.02f + (PI.toFloat() + 0.04f) * i / n
                val rr = r - s * 0.075f * sin(t) * thick
                val p = c + dip * sin(t) + Offset(cos(t), sin(t)) * rr
                lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(cradlePath, cradle, alpha = alpha * frame.cradle.coerceIn(0f, 1f))
    }
    val dawn = c + Offset(cos(212f * PI.toFloat() / 180f), sin(212f * PI.toFloat() / 180f)) * r
    val rest = c + Offset(r * 0.28f, r * 0.68f + frame.squash * s)
    // 飘落的花瓣：二次贝塞尔从花到掌心，控制点在右上（先被风托起再落下），身后三粒轨迹点渐淡
    val fall = frame.fall
    if (fall == null) {
        petal(rest, s * 0.26f, 72f, petal, alpha)
    } else if (fall > 0f) {
        val ctrl = c + Offset(r * 0.75f, -r * 0.65f)
        for (k in 1..3) {
            val tt = fall - k * 0.11f
            if (tt <= 0f) continue
            drawCircle(petal, radius = s * (0.035f - k * 0.006f), center = quadBezier(dawn, ctrl, rest, easeInOutCubic(tt)), alpha = alpha * (0.75f - k * 0.18f))
        }
        val e = easeInOutCubic(fall)
        petal(quadBezier(dawn, ctrl, rest, e), s * (0.2f + 0.06f * e), 72f + 260f * (1 - e), petal, alpha)
    }
    // 晨光处的花：每片花瓣按自己的开放程度缩放
    val flower = Path()
    for (i in 0 until 5) {
        val len = s * 0.25f * frame.bloom[i].coerceAtLeast(0f)
        if (len > 0.5f) flower.addPetal(dawn, len, -8 + i * 72f + frame.sway, offset = s * 0.02f)
    }
    drawPath(flower, petal, alpha = alpha)
    val heartScale = frame.bloom.average().toFloat().coerceIn(0f, 1.2f)
    if (heartScale > 0f) drawCircle(heart, radius = s * 0.065f * heartScale, center = dawn, alpha = alpha)
}

/**
 * 标志的轨迹动画（朝花夕拾），总长 2.4 秒：
 * 0.00–0.70s 日光弧从左往右画出，进度 = 1 − 2^(−10t)；
 * 0.15–0.70s 掌心浮现；
 * 0.45–1.10s 五片花瓣依次开放，间隔 70ms，弹簧阶跃（ζ = 0.45，带过冲）；
 * 1.10–2.00s 一片花瓣沿贝塞尔曲线飘进掌心；
 * 2.00s 起  掌心接住，冲击响应 x(t) = e^(−ζω₀t)·sin(ω_d t) 让它下沉再弹回。
 */
fun dayFrame(time: Float, idle: Float = 0f): DayFrame {
    fun span(from: Float, to: Float) = ((time - from) / (to - from)).coerceIn(0f, 1f)
    val bloom = FloatArray(5) { i ->
        val t0 = 0.45f + i * 0.07f
        if (time < t0) 0f else springStep(time - t0, response = 0.42f, damping = 0.45f)
    }
    val fall = if (time < 2.0f) span(1.1f, 2.0f) else null
    val squash = if (time >= 2.0f) springImpulse(time - 2.0f, response = 0.36f, damping = 0.35f) * 0.035f else 0f
    return DayFrame(
        light = easeOutExpo(span(0f, 0.7f)),
        cradle = easeOutExpo(span(0.15f, 0.7f)),
        bloom = bloom,
        fall = fall,
        squash = squash,
        sway = 6f * sin(TAU * idle),
    )
}

/**
 * 拾日的标志。animate 为 true 时：出现时演一遍轨迹动画，之后每 10 秒演一遍；轻触立刻重播并重新计时，平时花随风微摆。
 */
@Composable
fun DayMark(size: Dp, modifier: Modifier = Modifier, animate: Boolean = false) {
    if (!animate || rememberReduceMotion()) {
        Canvas(modifier.size(size)) { drawDay() }
        return
    }
    val clock = remember { Animatable(0f) }
    val plays = remember { mutableIntStateOf(0) }
    val idle by rememberInfiniteTransition(label = "sway").animateFloat(0f, 1f, infiniteRepeatable(tween(4_200, easing = LinearEasing)), label = "idle")
    LaunchedEffect(plays.intValue) {
        while (true) {
            clock.snapTo(0f)
            clock.animateTo(3.2f, tween(3_200, easing = LinearEasing))
            delay(6_800) // 每 10 秒演一遍（动画 3.2 秒 + 停 6.8 秒）
        }
    }
    Canvas(
        modifier.size(size).pointerInput(Unit) { detectTapGestures { plays.intValue++ } },
    ) { drawDay(frame = dayFrame(clock.value, idle)) }
}

/** 时间线：两张卡片，一淡紫一蜜桃，左侧各有一个时间点。 */
fun DrawScope.drawTimeline(alpha: Float) {
    val s = size.minDimension
    val stroke = Stroke(s * 0.085f, cap = StrokeCap.Round)
    fun card(top: Float, color: Color) {
        drawRoundRect(color, Offset(s * 0.30f, top), Size(s * 0.56f, s * 0.26f), androidx.compose.ui.geometry.CornerRadius(s * 0.08f), alpha = alpha, style = stroke)
        drawCircle(color, radius = s * 0.065f, center = Offset(s * 0.14f, top + s * 0.13f), alpha = alpha)
    }
    drawLine(Hue.Lilac.copy(alpha = 0.45f * alpha), Offset(s * 0.14f, s * 0.2f), Offset(s * 0.14f, s * 0.8f), s * 0.04f, StrokeCap.Round)
    card(s * 0.17f, Hue.Lilac)
    card(s * 0.57f, Hue.Peach)
}

/** 总结：四块归类的小方格，各一种颜色，像把日子分门别类。 */
fun DrawScope.drawSummary(alpha: Float) {
    val s = size.minDimension
    val stroke = Stroke(s * 0.085f, cap = StrokeCap.Round)
    val r = androidx.compose.ui.geometry.CornerRadius(s * 0.09f)
    val cell = s * 0.32f
    listOf(Offset(s * 0.14f, s * 0.14f) to Hue.Rose, Offset(s * 0.54f, s * 0.14f) to Hue.Sky, Offset(s * 0.14f, s * 0.54f) to Hue.Gold, Offset(s * 0.54f, s * 0.54f) to Hue.Lilac)
        .forEachIndexed { i, (o, c) ->
            if (i == 0) drawRoundRect(c, o, Size(cell, cell), r, alpha = alpha)
            else drawRoundRect(c, o, Size(cell, cell), r, alpha = alpha, style = stroke)
        }
}

/** 采集：五根声纹柱，每根一种颜色，高度像一句话的起伏。 */
fun DrawScope.drawVoice(alpha: Float, levels: FloatArray = floatArrayOf(0.32f, 0.62f, 0.86f, 0.54f, 0.28f)) {
    val s = size.minDimension
    val colors = listOf(Hue.Sky, Hue.Lilac, Hue.Rose, Hue.Peach, Hue.Gold)
    for (i in 0 until 5) {
        val x = s * (0.14f + 0.18f * i)
        val h = s * levels[i]
        drawLine(colors[i], Offset(x, s / 2 - h / 2), Offset(x, s / 2 + h / 2), s * 0.11f, StrokeCap.Round, alpha = alpha)
    }
}

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 多色的底栏图标。选中：颜色饱满，加顶光（上亮下暗）、柔和投影，出现时一道高光扫过；
 * 未选中：同样的图形和颜色，降到 45% 不透明度，不加光影。
 */
@Composable
fun Glyph(selected: Boolean, size: Dp = 24.dp, draw: DrawScope.(alpha: Float) -> Unit) {
    val sweep = remember { Animatable(if (selected) 0f else 1f) }
    val reduce = rememberReduceMotion()
    LaunchedEffect(selected) { if (selected && !reduce) { sweep.snapTo(0f); sweep.animateTo(1f, tween(1_000, easing = Emphasized)) } }
    val dark = Palette.dark
    Box(Modifier.size(size)) {
        if (selected) {
            Canvas(Modifier.size(size).offset(y = 1.5.dp).blur(2.5.dp).graphicsLayer { alpha = if (dark) 0.6f else 0.35f }) {
                // 投影：同一图形，压成深色
                drawIntoShadow { draw(1f) }
            }
        }
        Canvas(
            Modifier.size(size)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (!selected) return@drawWithContent
                    drawRect(
                        Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.12f else 0.28f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = if (dark) 0.25f else 0.16f)),
                        blendMode = BlendMode.SrcAtop,
                    )
                    val p = sweep.value
                    if (p < 1f) {
                        val x = this.size.width * (-0.7f + 2.4f * p)
                        val band = this.size.width * 0.32f
                        drawRect(
                            Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent), Offset(x - band, -band), Offset(x + band, this.size.height + band)),
                            blendMode = BlendMode.SrcAtop,
                        )
                    }
                },
        ) { draw(if (selected) 1f else 0.45f) }
    }
}

/** 把一段绘制压成深色剪影（用于投影）：先画，再用 SrcIn 填成黑色。 */
private inline fun DrawScope.drawIntoShadow(block: DrawScope.() -> Unit) {
    drawContext.canvas.saveLayer(Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint())
    block()
    drawRect(Color(0xFF2A1830), blendMode = BlendMode.SrcIn)
    drawContext.canvas.restore()
}
