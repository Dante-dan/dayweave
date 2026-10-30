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
import app.dayweave.ui.motion.petal
import app.dayweave.ui.theme.Glass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlin.math.cos
import kotlin.math.sin

/** 根布局提供的背景模糊源；悬浮的玻璃元素（导航栏、浮层）用它做真实的背景模糊。 */
val LocalHaze = compositionLocalOf<HazeState?> { null }

val GlassStyle get() = HazeStyle(
    backgroundColor = Glass.Base,
    tint = HazeTint(Glass.Veil.copy(alpha = if (app.dayweave.ui.theme.Palette.dark) 0.45f else 0.55f)),
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
    // 落花的时钟：一分钟一个循环，每片花瓣按自己的速度取模，循环处看不出接缝。
    val clock by t.animateFloat(0f, 60f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "petals")
    val still = app.dayweave.ui.motion.rememberReduceMotion()
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
            if (!still) driftingPetals(clock)
        }
        content()
    }
}

/**
 * 背景里缓缓飘落的几片花瓣（朝花夕拾）。每片花瓣的轨迹是：
 * y(t) = (y₀ + v·t) mod (H + 2s) − s（匀速下落、循环），
 * x(t) = x₀ + A·sin(ωt + φ) + d·t（左右摇摆、带一点风向漂移），
 * 旋转 r(t) = r₀ + 38°·sin(0.7ωt + φ)。透明度很低，只做氛围。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.driftingPetals(clock: Float) {
    val w = size.width
    val h = size.height
    val s = w * 0.035f
    for (i in 0 until 7) {
        val speed = h * (0.018f + 0.012f * app.dayweave.ui.motion.seed(i, 21)) // 每秒下落屏高的 1.8%–3%
        val omega = 0.5f + 0.35f * app.dayweave.ui.motion.seed(i, 22)
        val phase = app.dayweave.ui.motion.seed(i, 23) * app.dayweave.ui.motion.TAU
        val span = h + 2 * s
        val y = ((h * app.dayweave.ui.motion.seed(i, 24) + speed * clock) % span) - s
        val x = w * (0.08f + 0.84f * app.dayweave.ui.motion.seed(i, 25)) + w * 0.05f * sin(omega * clock + phase) + w * 0.012f * clock % w
        val rot = 360f * app.dayweave.ui.motion.seed(i, 26) + 38f * sin(0.7f * omega * clock + phase)
        val size = s * (0.75f + 0.6f * app.dayweave.ui.motion.seed(i, 27))
        // 夜里的落花不能是灰的：换成带一点光的玫瑰与淡紫，透明度更低
        val dark = app.dayweave.ui.theme.Palette.dark
        val tint = if (dark) NightPetals[i % NightPetals.size] else app.dayweave.ui.motion.PetalTints[i % 6]
        petal(Offset(x % w, y), size, rot, tint, alpha = if (dark) 0.26f else 0.38f)
    }
}

private val NightPetals = listOf(Color(0xFFFF8FBF), Color(0xFFC9A8FF), Color(0xFFFFB38F), Color(0xFFFF7FAF))
