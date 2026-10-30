package app.dayweave.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Ink {
    val Background = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFAFAFA)
    val Fill = Color(0xFFEFEFEF)
    val Hairline = Color(0xFFDBDBDB)
    val Primary = Color(0xFF0F0F0F)
    val Secondary = Color(0xFF737373)
    val Tertiary = Color(0xFFA8A8A8)
    val Link = Color(0xFF0095F6)
    val Danger = Color(0xFFED4956)
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

/** 极光背景与玻璃材质：Apple Liquid Glass 的通透感，配 ins 式的清新粉彩。 */
object Glass {
    val Base = Color(0xFFFBF8FC)
    val Peach = Color(0xFFFFD9C7)
    val Pink = Color(0xFFFFC9E0)
    val Lavender = Color(0xFFDCD2FF)
    val Sky = Color(0xFFC9E6FF)
    val Butter = Color(0xFFFFF0BF)
    val Mint = Color(0xFFCDF3E4)

    /** 玻璃底色：白色半透明。 */
    val Fill = Color.White.copy(alpha = 0.58f)
    val FillStrong = Color.White.copy(alpha = 0.78f)
    /** 上沿高光到下沿淡出的描边，模拟玻璃边缘的折光。 */
    val Edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f)))
    val Shadow = Color(0xFF6B4E9B)
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
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, color = Ink.Secondary)
    val Micro = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, color = Ink.Tertiary)
    val Prompt = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp)
}

@Composable
fun DayweaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink.Primary,
            onPrimary = Color.White,
            background = Ink.Background,
            surface = Ink.Background,
            onSurface = Ink.Primary,
            surfaceVariant = Ink.Fill,
            outline = Ink.Hairline,
            error = Ink.Danger,
        ),
        content = content,
    )
}
