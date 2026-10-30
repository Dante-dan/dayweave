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
import androidx.compose.runtime.remember
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

/** 画“日”：单位坐标 0..1 映射到 size。dim 用于未选中（整体降不透明度）。 */
fun DrawScope.drawDay(alpha: Float = 1f, light: Color = Hue.Lilac, cradle: Color = Hue.Rose, petal: Color = Hue.Blush, heart: Color = Hue.Gold) {
    val s = size.minDimension
    val c = Offset(size.width / 2, size.height * 0.52f)
    val r = s * 0.36f
    // 日光的轨迹：上半圆，细
    drawArc(light, 182f, 176f, false, topLeft = c - Offset(r, r), size = Size(2 * r, 2 * r), alpha = alpha, style = Stroke(s * 0.065f, cap = StrokeCap.Round))
    // 掌心：下半圆，中间厚两头尖的月牙
    val cradlePath = Path().apply {
        val n = 32
        for (i in 0..n) {
            val t = -0.02f + (PI.toFloat() + 0.04f) * i / n
            val rr = r + s * 0.05f * sin(t)
            val p = c + Offset(cos(t), sin(t)) * rr
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        for (i in n downTo 0) {
            val t = -0.02f + (PI.toFloat() + 0.04f) * i / n
            val rr = r - s * 0.075f * sin(t)
            val p = c + Offset(cos(t), sin(t)) * rr
            lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(cradlePath, cradle, alpha = alpha)
    // 掌心里的花瓣
    val rest = c + Offset(r * 0.28f, r * 0.68f)
    petal(rest, s * 0.26f, 72f, petal, alpha)
    // 晨光处的花
    val dawn = c + Offset(cos(212f * PI.toFloat() / 180f), sin(212f * PI.toFloat() / 180f)) * r
    val flower = Path()
    for (i in 0 until 5) flower.addPetal(dawn, s * 0.25f, -8 + i * 72f, offset = s * 0.02f)
    drawPath(flower, petal, alpha = alpha)
    drawCircle(heart, radius = s * 0.065f, center = dawn, alpha = alpha)
}

/** 拾日的标志，用在首页标题旁、空状态。 */
@Composable
fun DayMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawDay() }
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
