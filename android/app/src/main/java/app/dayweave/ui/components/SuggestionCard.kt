package app.dayweave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dayweave.ai.Suggestion
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 拾日主动提出的一件事：时间、来由、原话，一键交给闹钟或日历。 */
@Composable
fun SuggestionCard(
    s: Suggestion,
    onAction: (String) -> Unit,
    onSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (s.kind) {
        "alarm" -> Icons.Rounded.Alarm
        "event" -> Icons.Rounded.Event
        else -> Icons.Rounded.CheckCircleOutline
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ink.StoryBrush).padding(1.5.dp)
            .clip(RoundedCornerShape(23.dp)).background(Color.White.copy(alpha = 0.92f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Ink.StoryBrush), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                s.at?.let { Text(when_(it), style = Type.Micro.copy(color = Ink.Pink, fontWeight = FontWeight.SemiBold)) }
                Text(s.title, style = Type.Headline)
            }
        }
        if (s.why.isNotBlank()) Text(s.why, style = Type.Body.copy(color = Ink.Primary.copy(alpha = 0.82f)))
        if (s.quote.isNotBlank()) {
            Text(
                "“${s.quote}”",
                style = Type.Caption.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.pressable(onSource, scaleTo = 0.98f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.at != null) {
                Chip(Icons.Rounded.Alarm, if (s.kind == "event") "提前 15 分钟" else "设闹钟", dark = s.kind == "alarm") { onAction("alarm") }
                Chip(Icons.Rounded.Event, "加到日程", dark = s.kind == "event") { onAction("event") }
            } else {
                Chip(Icons.Rounded.CheckCircleOutline, "记下了", dark = true) { onAction("done") }
            }
            Spacer(Modifier.weight(1f))
            Text("忽略", style = Type.Caption, modifier = Modifier.pressable({ onAction("dismiss") }).padding(8.dp))
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, text: String, dark: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (dark) Ink.Primary else Ink.Fill).pressable(onClick, scaleTo = 0.94f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, tint = if (dark) Color.White else Ink.Primary, modifier = Modifier.size(15.dp))
        Text(text, style = Type.Caption.copy(color = if (dark) Color.White else Ink.Primary, fontWeight = FontWeight.SemiBold))
    }
}

private fun when_(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return "${dayLabel(t.toLocalDate())} ${t.format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA))}"
}
