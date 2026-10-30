package app.dayweave.ui.motion

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dayweave.ui.theme.Ink
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 拾日的动效都用解析式计算，不依赖逐帧积分：给定时间 t 就能算出位置，所以任何一帧都可以直接画，
 * 也不会因为掉帧而累积误差。参数沿用 Apple 的弹簧描述方式：response（周期，秒）与 dampingFraction（阻尼比）。
 */

const val TAU = (2 * PI).toFloat()

/**
 * 欠阻尼弹簧从 0 走向 1 的位移（阶跃响应）：
 * x(t) = 1 − e^(−ζω₀t) · (cos ω_d t + ζ/√(1−ζ²) · sin ω_d t)，ω₀ = 2π / response，ω_d = ω₀√(1−ζ²)。
 */
fun springStep(t: Float, response: Float = 0.42f, damping: Float = 0.72f): Float {
    val w0 = TAU / response
    val root = sqrt(1 - damping * damping)
    val wd = w0 * root
    return 1 - exp(-damping * w0 * t) * (cos(wd * t) + damping / root * sin(wd * t))
}

/**
 * 被轻轻“弹”了一下的弹簧：从静止位置以单位初速度出发，最后回到 0。
 * x(t) = e^(−ζω₀t) · sin(ω_d t) / ω_d，再归一化到峰值约为 1，方便乘上想要的幅度。
 */
fun springImpulse(t: Float, response: Float = 0.38f, damping: Float = 0.42f): Float {
    val w0 = TAU / response
    val wd = w0 * sqrt(1 - damping * damping)
    val peak = exp(-damping * w0 * (kotlin.math.atan(wd / (damping * w0)) / wd)) // 第一个波峰处的衰减
    return exp(-damping * w0 * t) * sin(wd * t) / peak
}

/**
 * 带线性空气阻力的抛体（花瓣比石头轻，阻力明显）：
 * p(t) = p₀ + v₀ · (1 − e^(−kt)) / k + g · (t/k − (1 − e^(−kt)) / k²)
 * 速度会自然趋向终端速度 g/k，看起来像花瓣在空中被托住。
 */
fun projectile(p0: Offset, v0: Offset, gravity: Offset, drag: Float, t: Float): Offset {
    val e = (1 - exp(-drag * t)) / drag
    val g = t / drag - e / drag
    return Offset(p0.x + v0.x * e + gravity.x * g, p0.y + v0.y * e + gravity.y * g)
}

fun quadBezier(p0: Offset, p1: Offset, p2: Offset, t: Float): Offset {
    val u = 1 - t
    return p0 * (u * u) + p1 * (2 * u * t) + p2 * (t * t)
}

fun easeOutExpo(t: Float) = if (t >= 1f) 1f else 1 - 2f.pow(-10 * t)
fun easeInOutCubic(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
/** 0 → 1 → 0 的钟形，用于淡入淡出。 */
fun bell(t: Float) = sin(PI.toFloat() * t.coerceIn(0f, 1f))

/** 稳定的伪随机数：同一个粒子每一帧拿到同样的值，不用存状态。 */
fun seed(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
}

/** 系统“移除动画”打开时，只保留必要的状态变化，不放装饰粒子。 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * 樱花式的花瓣，带缺口。单位花瓣朝上、根部在原点，再按长度、角度变换。
 * 和图标（android/tools/icon.py）用的是同一组控制点。
 */
private val PETAL = floatArrayOf(
    -0.5f, -0.18f, -0.54f, -0.86f, -0.24f, -1f, // C
    0f, -0.86f, // L
    0.24f, -1f, // L
    0.54f, -0.86f, 0.5f, -0.18f, 0f, 0f, // C
)

fun Path.addPetal(center: Offset, length: Float, angleDeg: Float, offset: Float = 0f) {
    val a = angleDeg * PI.toFloat() / 180f
    val c = cos(a)
    val s = sin(a)
    fun x(ux: Float, uy: Float) = center.x + ux * length * c - (uy * length - offset) * s
    fun y(ux: Float, uy: Float) = center.y + ux * length * s + (uy * length - offset) * c
    moveTo(x(0f, 0f), y(0f, 0f))
    val p = PETAL
    cubicTo(x(p[0], p[1]), y(p[0], p[1]), x(p[2], p[3]), y(p[2], p[3]), x(p[4], p[5]), y(p[4], p[5]))
    lineTo(x(p[6], p[7]), y(p[6], p[7]))
    lineTo(x(p[8], p[9]), y(p[8], p[9]))
    cubicTo(x(p[10], p[11]), y(p[10], p[11]), x(p[12], p[13]), y(p[12], p[13]), x(p[14], p[15]), y(p[14], p[15]))
    close()
}

/** 画一片花瓣：center 是花瓣中心，size 是花瓣长度。 */
fun DrawScope.petal(center: Offset, size: Float, rotationDeg: Float, color: Color, alpha: Float = 1f) {
    if (alpha <= 0.01f || size <= 0.5f) return
    rotate(rotationDeg, center) {
        val path = Path().apply { addPetal(Offset(center.x, center.y + size / 2), size, 0f) }
        drawPath(path, color, alpha = alpha.coerceIn(0f, 1f))
    }
}

/** 花瓣的配色：晨光的桃与粉、暮色的紫，混一点白。 */
val PetalTints = listOf(
    Color(0xFFFFB7C9), Color(0xFFFFD0B5), Color(0xFFF7A1C4), Color(0xFFD9C6FF), Color(0xFFFFFFFF), Color(0xFFFFC2D6),
)

/** 拾日的花：五瓣樱花，渐变着色，可以缓缓旋转。用于标志、加载、空状态。 */
@Composable
fun BlossomMark(size: Dp, modifier: Modifier = Modifier, rotation: Float = 0f, brush: Brush = Ink.StoryBrush, center: Color = Color(0xFFFFD983)) {
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val len = this.size.minDimension * 0.47f
        val path = Path()
        for (i in 0 until 5) path.addPetal(c, len, rotation - 8 + i * 72f, offset = len * 0.07f)
        drawPath(path, brush)
        drawCircle(center, radius = len * 0.17f, center = c)
    }
}

/** 底栏用的花形图标：实心表示选中，描边表示未选中。 */
fun blossomIcon(filled: Boolean): ImageVector {
    val nodes = PathBuilder().apply {
        for (i in 0 until 5) {
            val a = (-8 + i * 72f) * PI.toFloat() / 180f
            val c = cos(a)
            val s = sin(a)
            fun x(ux: Float, uy: Float) = 12f + ux * 10.2f * c - (uy * 10.2f - 0.6f) * s
            fun y(ux: Float, uy: Float) = 12.3f + ux * 10.2f * s + (uy * 10.2f - 0.6f) * c
            val p = PETAL
            moveTo(x(0f, 0f), y(0f, 0f))
            curveTo(x(p[0], p[1]), y(p[0], p[1]), x(p[2], p[3]), y(p[2], p[3]), x(p[4], p[5]), y(p[4], p[5]))
            lineTo(x(p[6], p[7]), y(p[6], p[7]))
            lineTo(x(p[8], p[9]), y(p[8], p[9]))
            curveTo(x(p[10], p[11]), y(p[10], p[11]), x(p[12], p[13]), y(p[12], p[13]), x(p[14], p[15]), y(p[14], p[15]))
            close()
        }
    }.nodes
    val builder = ImageVector.Builder("Blossom", 24.dp, 24.dp, 24f, 24f)
    return if (filled) {
        builder.addPath(nodes, fill = SolidColor(Color.Black)).build()
    } else {
        builder.addPath(nodes, fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round).build()
    }
}
