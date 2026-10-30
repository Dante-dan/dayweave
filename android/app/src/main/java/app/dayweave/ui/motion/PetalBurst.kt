package app.dayweave.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

/** 花瓣的三种运动。 */
enum class Petals {
    /** 开启：从中心向四周绽开，受重力和空气阻力，像一阵风把花吹散。 */
    BLOOM,

    /** 关闭：围在四周的花瓣慢慢飘落，左右摇摆，像夕阳下落花。 */
    FALL,

    /** 命名、完成：花瓣绕着中心转半圈再散开，像被轻轻拾起。 */
    ORBIT,
}

/**
 * 一次性的花瓣动效。trigger 变化时播放一次；第一次组合时不播放。
 * 画在自身范围之外也可以（不裁剪），通常叠在按钮或头像上，origin 为空时以中心为原点。
 */
@Composable
fun PetalBurst(
    trigger: Any?,
    mode: Petals,
    modifier: Modifier = Modifier,
    count: Int = 14,
    durationMs: Int = 1_200,
    radius: Float = 0f,
) {
    if (rememberReduceMotion()) return
    val progress = remember { Animatable(1f) }
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        if (first.value) {
            first.value = false
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = LinearEasing))
    }
    Canvas(modifier) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val center = Offset(size.width / 2, size.height / 2)
        val r0 = if (radius > 0f) radius else size.minDimension * 0.42f
        val unit = size.minDimension
        val t = p * durationMs / 1000f // 秒
        for (i in 0 until count) {
            val angle = TAU * i / count + (seed(i, 1) - 0.5f) * 0.6f
            val tint = PetalTints[i % PetalTints.size]
            val petalSize = unit * (0.07f + 0.05f * seed(i, 2))
            when (mode) {
                Petals.BLOOM -> {
                    val speed = unit * (2.4f + 1.6f * seed(i, 3))
                    val start = center + Offset(cos(angle), sin(angle)) * (r0 * 0.55f)
                    val v0 = Offset(cos(angle), sin(angle)) * speed + Offset(0f, -unit * 0.9f)
                    val pos = projectile(start, v0, Offset(0f, unit * 4.2f), drag = 3.2f, t = t)
                    // 先在 0.12 秒内弹出来（带过冲），再慢慢淡去
                    val pop = springStep(t, response = 0.32f, damping = 0.55f).coerceAtLeast(0f)
                    val spin = (seed(i, 4) - 0.5f) * 720f * t + angle * 57.3f + 90f
                    petal(pos, petalSize * pop, spin, tint, alpha = (1 - p) * (1 - p) * 1.1f)
                }
                Petals.FALL -> {
                    // 从花的位置出发：y 随时间做重力下落，x 用正弦摆动，旋转跟着摆动的相位
                    val start = center + Offset(cos(angle), sin(angle)) * r0
                    val sway = unit * 0.09f * sin(TAU * (0.9f + 0.4f * seed(i, 5)) * t + seed(i, 6) * TAU)
                    val fall = 0.5f * unit * (1.3f + 0.8f * seed(i, 7)) * t * t
                    val pos = start + Offset(sway, fall)
                    val spin = angle * 57.3f + 50f * sin(TAU * 0.9f * t + i)
                    petal(pos, petalSize, spin, tint, alpha = bell(0.15f + p * 0.85f) * 0.9f)
                }
                Petals.ORBIT -> {
                    // 极坐标：θ 走过 3/4 圈（缓入缓出），半径在中段膨胀再收，最后向外散开
                    val theta = angle + TAU * 0.75f * easeInOutCubic(p)
                    val r = r0 * (1f + 0.22f * sin(TAU * 0.5f * p)) + unit * 0.35f * easeOutExpo(((p - 0.7f) / 0.3f).coerceIn(0f, 1f))
                    val pos = center + Offset(cos(theta), sin(theta)) * r
                    petal(pos, petalSize * 0.85f, theta * 57.3f + 90f, tint, alpha = bell(p))
                }
            }
        }
    }
}
