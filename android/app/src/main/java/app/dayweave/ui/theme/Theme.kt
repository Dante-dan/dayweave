package app.dayweave.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 当前是否为深色（“夕”）。颜色令牌都通过它取值；它是可观察状态，切换时用到颜色的界面会自动重绘。
 * 由 [DayweaveTheme] 根据“外观”设置和系统深色模式写入。
 */
object Palette {
    var dark by mutableStateOf(false)
}

private fun pick(light: Color, dark: Color) = if (Palette.dark) dark else light

object Ink {
    val Background get() = pick(Color(0xFFFFFFFF), Color(0xFF0F0D15))
    val Surface get() = pick(Color(0xFFFAFAFA), Color(0xFF1A1722))
    val Fill get() = pick(Color(0xFFEFEFEF), Color(0xFF2A2633))
    val Hairline get() = pick(Color(0xFFDBDBDB), Color(0xFF3A3546))
    /** 主文字色，也用作实心按钮的底色。 */
    val Primary get() = pick(Color(0xFF0F0F0F), Color(0xFFF3F0F8))
    /** 放在 [Primary] 底色上的文字和图标。 */
    val OnPrimary get() = pick(Color(0xFFFFFFFF), Color(0xFF15121C))
    val Secondary get() = pick(Color(0xFF737373), Color(0xFFA9A3B8))
    val Tertiary get() = pick(Color(0xFFA8A8A8), Color(0xFF6F6880))
    val Link get() = pick(Color(0xFF0095F6), Color(0xFF4DB2FF))
    val Danger get() = pick(Color(0xFFED4956), Color(0xFFFF6B78))
    /** 强调色：朝花的玫瑰粉。只用在“当前选中”这类需要一点颜色的地方。 */
    val Accent get() = pick(Color(0xFFE34F86), Color(0xFFFF8DB6))
    val Live = Color(0xFFFF3040)

    val Yellow = Color(0xFFFEDA75)
    val Orange = Color(0xFFFA7E1E)
    val Pink = Color(0xFFD62976)
    val Purple = Color(0xFF962FBF)
    val Blue = Color(0xFF4F5BD5)

    /** 快拍光环的经典渐变，只在关键位置使用。 */
    val Story = listOf(Yellow, Orange, Pink, Purple, Blue)
    val StorySweep = listOf(Yellow, Orange, Pink, Purple, Blue, Purple, Pink, Orange, Yellow)

    /** 主人气泡：紫到粉，克制一点。 */
    val OwnerBubble = Brush.linearGradient(listOf(Color(0xFF7B3FE4), Color(0xFFC13584), Color(0xFFE1306C)))
    val StoryBrush = Brush.linearGradient(Story)
}

/**
 * 极光背景与玻璃材质。浅色是“朝”：Apple Liquid Glass 的通透感，配 ins 式的清新粉彩；
 * 深色是“夕”：深靛的夜色，极光压暗成暮色里的玫瑰、紫与青，玻璃变成烟色。
 */
object Glass {
    val Base get() = pick(Color(0xFFFBF8FC), Color(0xFF0E0C14))
    val Peach get() = pick(Color(0xFFFFD9C7), Color(0xFF3A2230))
    val Pink get() = pick(Color(0xFFFFC9E0), Color(0xFF3B1C38))
    val Lavender get() = pick(Color(0xFFDCD2FF), Color(0xFF26214D))
    val Sky get() = pick(Color(0xFFC9E6FF), Color(0xFF12263F))
    val Butter get() = pick(Color(0xFFFFF0BF), Color(0xFF2E2618))
    val Mint get() = pick(Color(0xFFCDF3E4), Color(0xFF10302A))

    /** 浮在背景上的面：浅色是白，深色是烟灰。按需要再调透明度。 */
    val Veil get() = pick(Color.White, Color(0xFF2B2737))

    /** 玻璃底色。 */
    val Fill get() = pick(Color.White.copy(alpha = 0.58f), Color.White.copy(alpha = 0.07f))
    val FillStrong get() = pick(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0.12f))
    /** 上沿高光到下沿淡出的描边，模拟玻璃边缘的折光。 */
    val Edge get() = if (Palette.dark) EdgeDark else EdgeLight
    val Shadow get() = pick(Color(0xFF6B4E9B), Color(0xFF000000))

    private val EdgeLight = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f)))
    private val EdgeDark = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)))
}

/** 匿名说话人的头像底色：柔和、彼此区分度高。 */
private val PersonColors = listOf(
    Color(0xFF7B61FF), Color(0xFF00A3A3), Color(0xFFFF7A59), Color(0xFF3D8BFD),
    Color(0xFFE0A100), Color(0xFF9C5DE0), Color(0xFF2EB67D), Color(0xFFE5537A),
)

fun personColor(index: Int): Color = PersonColors[Math.floorMod(index, PersonColors.size)]

object Type {
    val Wordmark = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.5).sp)
    val Display = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.6).sp, lineHeight = 36.sp)
    val Title = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.3).sp)
    val Headline = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.1).sp)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val Caption get() = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, color = Ink.Secondary)
    val Micro get() = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, color = Ink.Tertiary)
    val Prompt = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp)
}

/** 外观：跟随系统、浅色（朝）、深色（夕）。 */
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("朝 · 浅色"), DARK("夕 · 深色") }

@Composable
fun DayweaveTheme(dark: Boolean, content: @Composable () -> Unit) {
    SideEffect { Palette.dark = dark }
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = Ink.Primary, onPrimary = Ink.OnPrimary, background = Ink.Background, surface = Ink.Surface,
                onSurface = Ink.Primary, onBackground = Ink.Primary, surfaceVariant = Ink.Fill, outline = Ink.Hairline, error = Ink.Danger,
            )
        } else {
            lightColorScheme(
                primary = Ink.Primary, onPrimary = Ink.OnPrimary, background = Ink.Background, surface = Ink.Background,
                onSurface = Ink.Primary, onBackground = Ink.Primary, surfaceVariant = Ink.Fill, outline = Ink.Hairline, error = Ink.Danger,
            )
        },
    ) {
        // Material 3 的 Text 在没有 Surface 时默认黑色，这里统一给主文字色。
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Ink.Primary, content = content)
    }
}
