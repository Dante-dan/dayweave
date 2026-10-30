package app.dayweave.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dayweave.core.Person
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import app.dayweave.ui.theme.personColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.dayweave.core.tr

/** 按下时轻微缩小、松开时弹回，代替 Material 的水波纹。 */
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true, scaleTo: Float = 0.96f): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) scaleTo else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/** 头像：主人带快拍渐变光环，其他人用纯色圆底和首字。 */
@Composable
fun Avatar(person: Person?, size: Dp, modifier: Modifier = Modifier, ring: Boolean = person?.isOwner == true) {
    val label = person?.initial ?: "?"
    val fill = when {
        person == null -> Ink.Tertiary
        person.isOwner -> Ink.Primary
        else -> personColor(person.colorIndex)
    }
    Box(
        modifier
            .size(size)
            .then(if (ring) Modifier.border(size * 0.06f, Ink.StoryBrush, CircleShape) else Modifier)
            .padding(if (ring) size * 0.1f else 0.dp)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (person?.isOwner == true) Ink.OnPrimary else Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
    }
}

/** 重叠排列的参与者头像，白色描边隔开。 */
@Composable
fun AvatarStack(people: List<Person?>, size: Dp = 30.dp, max: Int = 3) {
    val shown = people.take(max)
    Box(Modifier.height(size).padding(end = (size * 0.6f) * (shown.size - 1).coerceAtLeast(0))) {
        shown.forEachIndexed { i, p ->
            Box(
                Modifier
                    .offset(x = size * 0.6f * i)
                    .size(size)
                    .border(2.dp, Glass.Veil, CircleShape)
                    .padding(2.dp),
            ) { Avatar(p, size - 4.dp, ring = false) }
        }
    }
}

/** 转写中的占位闪光。 */
@Composable
fun ShimmerLines(lines: Int = 2, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == lines - 1) 0.6f else 1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .drawBehind {
                        drawRect(
                            Brush.linearGradient(
                                listOf(Ink.Fill, Ink.Surface, Ink.Fill),
                                start = Offset(size.width * x - size.width, 0f),
                                end = Offset(size.width * x, 0f),
                            ),
                        )
                    },
            )
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, active: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Ink.Primary else Ink.Fill)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(text, style = Type.Caption.copy(color = if (active) Ink.OnPrimary else Ink.Primary, fontWeight = FontWeight.Medium))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Headline, modifier = modifier)
}

/** 直播式的小红点，持续呼吸。 */
@Composable
fun LiveDot(color: Color = Ink.Live, size: Dp = 7.dp) {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).graphicsLayer { alpha = a }.clip(CircleShape).background(color))
}

private val zone = ZoneId.systemDefault()
private val hm = DateTimeFormatter.ofPattern("HH:mm")

fun clock(epochMs: Long): String = hm.format(Instant.ofEpochMilli(epochMs).atZone(zone))

fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now(zone)
    return when (date) {
        today -> tr("今天", "Today")
        today.minusDays(1) -> tr("昨天", "Yesterday")
        else -> longDate(date)
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> tr("${s} 秒", "${s}s")
        s < 3600 -> tr("${s / 60} 分 ${s % 60} 秒", "${s / 60}m ${s % 60}s")
        else -> tr("${s / 3600} 小时 ${s % 3600 / 60} 分", "${s / 3600}h ${s % 3600 / 60}m")
    }
}

/** 列表里的总时长：不足 1 分钟时显示秒，避免出现“0 分钟”。 */
fun minutes(ms: Long): String = if (ms < 60_000) tr("${ms / 1000} 秒", "${ms / 1000} sec") else tr("${ms / 60_000} 分钟", "${ms / 60_000} min")

fun stopwatch(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}

/** “10月30日 星期五” / “Fri, Oct 30”。 */
fun longDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern(tr("M月d日 EEEE", "EEE, MMM d"), app.dayweave.core.Lang.locale))

/** “10月30日 21:43” / “Oct 30, 21:43”。 */
fun dateTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern(tr("M月d日 HH:mm", "MMM d, HH:mm"), app.dayweave.core.Lang.locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))
