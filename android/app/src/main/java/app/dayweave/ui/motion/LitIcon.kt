package app.dayweave.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Palette

/** Apple 常用的“强调减速”曲线：起步快，收尾很长。 */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 有光影的单色图标（颜色由 color 决定，通常是强调色）：
 * - 体积：图标本身按“上亮下暗”着色（顶部提亮约 22%，底部压暗约 20%），像有一束顶光；
 * - 投影：同形状的柔影向下偏 1.5dp、模糊 2.5dp，图标像浮在玻璃上；
 * - 高光：出现时一道斜向的镜面高光从左上扫到右下，位置 x(t) = −0.7w + 2.4w·ease(t)，扫过即止。
 * heart 不为空时，在花心点一粒暖色（只给“拾日”的花用）。
 */
@Composable
fun LitIcon(icon: ImageVector, description: String?, color: Color = Ink.Primary, iconSize: Dp = 24.dp, heart: Offset? = null) {
    val sweep = remember { Animatable(0f) }
    val reduce = rememberReduceMotion()
    LaunchedEffect(Unit) { if (!reduce) sweep.animateTo(1f, tween(1_000, easing = Emphasized)) else sweep.snapTo(1f) }
    val dark = Palette.dark
    Box(Modifier.size(iconSize)) {
        Icon(
            icon, null,
            tint = (if (dark) Color.Black else color).copy(alpha = if (dark) 0.55f else 0.32f),
            modifier = Modifier.matchParentSize().offset(y = 1.5.dp).blur(2.5.dp),
        )
        Icon(
            icon, description,
            tint = color,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (dark) 0.10f else 0.22f),
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = if (dark) 0.28f else 0.20f),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                    val p = sweep.value
                    if (p < 1f) {
                        val x = size.width * (-0.7f + 2.4f * p)
                        val band = size.width * 0.32f
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = if (dark) 0.5f else 0.75f), Color.Transparent),
                                start = Offset(x - band, -band), end = Offset(x + band, size.height + band),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    }
                },
        )
        if (heart != null) {
            Canvas(Modifier.matchParentSize()) {
                val c = Offset(size.width * heart.x, size.height * heart.y)
                drawCircle(Color(0xFFFFD983), radius = size.width * 0.085f, center = c)
                drawCircle(Color.White.copy(alpha = 0.7f), radius = size.width * 0.03f, center = c - Offset(size.width * 0.025f, size.width * 0.025f))
            }
        }
    }
}
