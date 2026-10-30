package app.dayweave.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import app.dayweave.ui.components.pressable
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val TAP_MS = 620

/**
 * 点按的反馈：按下的位置漾开一圈柔光（半径按 1 − 2^(−10t) 迅速铺开、透明度按 (1 − t)² 收），
 * 同时弹出几片小花瓣，沿抛物线落下。再叠加 [pressable] 的弹簧缩放。
 */
fun Modifier.bloomTap(
    onClick: () -> Unit,
    enabled: Boolean = true,
    scaleTo: Float = 0.97f,
    tint: Color = Color(0xFFFFB7C9),
): Modifier = composed {
    val reduce = rememberReduceMotion()
    val progress = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    this
        .pointerInput(enabled) {
            if (!enabled || reduce) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                origin = down.position
                scope.launch {
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(TAP_MS, easing = LinearEasing))
                }
            }
        }
        .drawWithContent {
            drawContent()
            val p = progress.value
            if (p >= 1f) return@drawWithContent
            val far = maxOf(
                hypot(origin.x, origin.y), hypot(size.width - origin.x, origin.y),
                hypot(origin.x, size.height - origin.y), hypot(size.width - origin.x, size.height - origin.y),
            )
            val r = far * easeOutExpo(p)
            drawCircle(
                Brush.radialGradient(listOf(tint.copy(alpha = 0.32f * (1 - p) * (1 - p)), Color.Transparent), center = origin, radius = r + 1f),
                radius = r, center = origin,
            )
            val t = p * TAP_MS / 1000f
            val unit = 120f
            for (i in 0 until 5) {
                val a = -1.57f + (i - 2) * 0.55f + (seed(i, 9) - 0.5f) * 0.3f // 主要向上散开
                val v0 = Offset(cos(a), sin(a)) * unit * (3.2f + seed(i, 10))
                val pos = projectile(origin, v0, Offset(0f, unit * 9f), drag = 3.5f, t = t)
                petal(pos, 11f + 5f * seed(i, 11), a * 57.3f + 90f + 300f * t, PetalTints[i], alpha = (1 - p) * 0.95f)
            }
        }
        .pressable(onClick, enabled = enabled, scaleTo = scaleTo)
}
