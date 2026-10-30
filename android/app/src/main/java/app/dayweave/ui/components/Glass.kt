package app.dayweave.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dayweave.ui.theme.Glass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlin.math.cos
import kotlin.math.sin

/** 根布局提供的背景模糊源；悬浮的玻璃元素（导航栏、浮层）用它做真实的背景模糊。 */
val LocalHaze = compositionLocalOf<HazeState?> { null }

val GlassStyle = HazeStyle(
    backgroundColor = Glass.Base,
    tint = HazeTint(Color.White.copy(alpha = 0.55f)),
    blurRadius = 28.dp,
    noiseFactor = 0.06f,
)

/**
 * 玻璃卡片：半透明白底 + 上亮下淡的描边 + 很柔的紫调阴影。
 * 列表里的卡片背后是静态的极光背景，用半透明就足够通透；悬浮层用 [frosted] 做真实模糊。
 */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), elevation: Dp = 10.dp, strong: Boolean = false): Modifier =
    this
        .outerShadow(shape, elevation)
        .clip(shape)
        .background(if (strong) Glass.FillStrong else Glass.Fill)
        .border(1.dp, Glass.Edge, shape)

/** 真实背景模糊的玻璃，用于悬浮在滚动内容之上的元素。 */
@Composable
fun Modifier.frosted(shape: Shape, elevation: Dp = 16.dp): Modifier {
    val haze = LocalHaze.current
    val base = this.outerShadow(shape, elevation).clip(shape)
    val blurred = if (haze != null) base.hazeChild(haze, shape, GlassStyle) else base.background(Glass.FillStrong)
    return blurred.border(1.dp, Glass.Edge, shape)
}

/**
 * 只画在形状外面的柔和阴影。系统阴影会透过半透明玻璃显示成一块灰色矩形，所以把形状内部裁掉。
 */
fun Modifier.outerShadow(shape: Shape, elevation: Dp): Modifier = drawWithCache {
    // 路径和画笔只在尺寸变化时重建，滚动和动画时不再每帧分配。
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }.asAndroidPath()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        setShadowLayer(elevation.toPx() * 1.6f, 0f, elevation.toPx() * 0.5f, Glass.Shadow.copy(alpha = 0.16f).toArgb())
    }
    onDrawBehind {
        if (elevation.value <= 0f) return@onDrawBehind
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipOutPath(path)
            native.drawPath(path, paint)
            native.restore()
        }
    }
}

/** 缓慢漂移的粉彩极光，铺在所有页面下面。 */
@Composable
fun Aurora(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(36_000, easing = LinearEasing)), label = "phase")
    val pulse by t.animateFloat(0.9f, 1.1f, infiniteRepeatable(tween(9_000), RepeatMode.Reverse), label = "pulse")
    Box(modifier.fillMaxSize().background(Glass.Base)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun blob(color: Color, cx: Float, cy: Float, r: Float, k: Float) {
                val c = Offset(w * cx + cos(phase * k) * w * 0.08f, h * cy + sin(phase * k) * h * 0.05f)
                drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = c, radius = r), radius = r, center = c)
            }
            blob(Glass.Peach, 0.10f, 0.06f, w * 0.75f * pulse, 1f)
            blob(Glass.Pink, 0.95f, 0.18f, w * 0.70f, -1f)
            blob(Glass.Lavender, 0.20f, 0.52f, w * 0.80f / pulse, 2f)
            blob(Glass.Sky, 0.95f, 0.70f, w * 0.75f, -2f)
            blob(Glass.Butter, 0.30f, 0.95f, w * 0.65f * pulse, 1f)
            blob(Glass.Mint, 0.85f, 1.02f, w * 0.55f, -1f)
        }
        content()
    }
}
