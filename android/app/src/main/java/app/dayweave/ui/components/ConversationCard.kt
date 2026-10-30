package app.dayweave.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dayweave.core.Person
import app.dayweave.core.Segment
import app.dayweave.core.SegmentStatus
import app.dayweave.core.SourceKind
import app.dayweave.ui.motion.bloomTap
import app.dayweave.ui.sharedSegment
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type

@Composable
fun SourceIcon(segment: Segment, modifier: Modifier = Modifier) {
    val icon = when {
        segment.source == SourceKind.PLAYBACK -> Icons.Rounded.PlayCircle
        segment.source == SourceKind.CALL -> Icons.Rounded.Call
        segment.device.contains("手机") -> Icons.Rounded.Mic
        else -> Icons.Rounded.Headphones
    }
    Icon(icon, contentDescription = null, tint = Ink.Secondary, modifier = modifier.size(14.dp))
}

/** 一段对话的卡片：时间、来源、参与者和开头几句。转写中显示占位闪光。 */
@Composable
fun ConversationCard(
    segment: Segment,
    people: Map<String, Person>,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .sharedSegment(segment.id)
            .bloomTap(onClick, enabled = segment.status == SegmentStatus.DONE, scaleTo = 0.98f)
            .glass(RoundedCornerShape(22.dp), elevation = 6.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarStack(segment.speakerIds.map { people[it] }.ifEmpty { listOf(null) })
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${clock(segment.startedAt)} – ${clock(segment.endedAt)}",
                    style = Type.Headline,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SourceIcon(segment)
                    Text(
                        "${if (segment.source == SourceKind.PLAYBACK) "播放声音" else segment.device} · ${duration(segment.durationMs)}",
                        style = Type.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        segment.scene?.let { SceneChip(it) }

        AnimatedContent(
            targetState = segment.status,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "status",
        ) { status ->
            when (status) {
                SegmentStatus.DONE -> Text(
                    segment.turns.take(3).joinToString("\n") { t ->
                        "${people[t.speakerId]?.name ?: "未识别"}：${t.text}"
                    },
                    style = Type.Body,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                SegmentStatus.PENDING, SegmentStatus.TRANSCRIBING -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShimmerLines()
                    Text(
                        if (status == SegmentStatus.TRANSCRIBING) "正在转写…" else (segment.error ?: "等待转写"),
                        style = Type.Micro,
                    )
                }
                SegmentStatus.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(segment.error ?: "转写失败", style = Type.Caption.copy(color = Ink.Danger), modifier = Modifier.weight(1f), maxLines = 2)
                    Text(
                        "重试",
                        style = Type.Caption.copy(color = Ink.Link, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.pressable(onRetry).padding(start = 12.dp),
                    )
                }
            }
        }
    }
}
