package app.dayweave.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dayweave.core.Person
import app.dayweave.core.SourceKind
import app.dayweave.core.Turn
import app.dayweave.graph
import app.dayweave.ui.Navigator
import app.dayweave.ui.components.Avatar
import app.dayweave.ui.components.SourceIcon
import app.dayweave.ui.components.clock
import app.dayweave.ui.components.dayLabel
import app.dayweave.ui.components.duration
import app.dayweave.ui.components.localDate
import app.dayweave.ui.components.pressable
import app.dayweave.ui.components.SceneChip
import app.dayweave.ui.sharedSegment
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import kotlinx.coroutines.delay

@Composable
fun ConversationScreen(id: String, nav: Navigator) {
    val graph = LocalContext.current.graph
    val segments by graph.archive.segments.collectAsStateWithLifecycle()
    val people by graph.voice.people.collectAsStateWithLifecycle()
    val segment = segments.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().sharedSegment(id).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundGlassButton(nav.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "返回", modifier = Modifier.size(18.dp).padding(start = 4.dp)) }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                if (segment != null) {
                    Text("${dayLabel(localDate(segment.startedAt))} ${clock(segment.startedAt)}", style = Type.Headline)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SourceIcon(segment)
                        Text(
                            "${if (segment.source == SourceKind.PLAYBACK) "播放声音" else segment.device} · ${duration(segment.durationMs)}",
                            style = Type.Caption,
                        )
                    }
                }
            }
            RoundGlassButton({ confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除", tint = Ink.Primary, modifier = Modifier.size(20.dp)) }
        }

        if (segment == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("这段对话已删除", style = Type.Caption) }
            return@Column
        }

        val participants = segment.speakerIds.map { people[it] }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item("people") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.padding(bottom = if (segment.scene != null) 10.dp else 18.dp),
                ) {
                    items(participants) { p ->
                        Column(
                            Modifier.pressable({ p?.let { nav.openPerson(it.id) } }, scaleTo = 0.9f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Avatar(p, 58.dp, ring = p?.isOwner == true || p?.named == false)
                            Text(
                                when {
                                    p == null -> "未识别"
                                    p.isOwner || p.named -> p.name
                                    else -> "${p.name} · 标注"
                                },
                                style = Type.Caption.copy(color = if (p != null && !p.isOwner && !p.named) Ink.Pink else Ink.Primary),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            segment.scene?.let { scene ->
                item("scene") {
                    Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                        SceneChip(scene, prefix = "这段对话发生在${scene.kind.doing.removeSuffix("时")}")
                    }
                }
            }
            itemsIndexed(segment.turns, key = { i, t -> "$i-${t.startMs}" }) { index, turn ->
                val previous = segment.turns.getOrNull(index - 1)
                Bubble(
                    turn = turn,
                    person = people[turn.speakerId],
                    showName = previous?.speakerId != turn.speakerId,
                    showTime = previous == null || turn.startMs - previous.endMs > 60_000,
                    segmentStart = segment.startedAt,
                    index = index,
                )
            }
            item("footer") {
                Text(
                    buildString {
                        val langs = segment.languages.map { if (it.startsWith("cmn")) "中文" else if (it.startsWith("en")) "English" else it }
                        if (langs.isNotEmpty()) append(langs.joinToString(" · ")).append("\n")
                        if (segment.speechMs > 0) append("有效人声 ${duration(segment.speechMs)}，静音与环境音未上传\n")
                        append("音频已在转写后删除，只保留文字")
                    },
                    style = Type.Micro,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这段对话？", style = Type.Title) },
            text = { Text("文字档案会从这台手机上永久删除。", style = Type.Body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    nav.back()
                    graph.archive.delete(id)
                }) { Text("删除", color = Ink.Danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Ink.Primary) } },
            containerColor = Ink.Surface,
        )
    }
}

/** 聊天气泡：主人在右侧用渐变，其他人在左侧用浅灰。依次淡入上浮。 */
@Composable
private fun Bubble(turn: Turn, person: Person?, showName: Boolean, showTime: Boolean, segmentStart: Long, index: Int) {
    val mine = person?.isOwner == true
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120L + (index.coerceAtMost(12) * 45L))
        appear.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * 24.dp.toPx() }
            .padding(top = if (showName) 10.dp else 0.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (showTime) {
            Text(clock(segmentStart + turn.startMs), style = Type.Micro, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
        if (showName && !mine) {
            Text(person?.name ?: "未识别", style = Type.Micro.copy(color = Ink.Secondary), modifier = Modifier.padding(start = 44.dp, bottom = 3.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (!mine) {
                Box(Modifier.size(30.dp)) { if (showName) Avatar(person, 30.dp, ring = false) }
                Spacer(Modifier.size(8.dp))
            }
            Text(
                turn.text,
                style = Type.Body.copy(color = if (mine) Color.White else Ink.Primary),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .then(
                        if (mine) Modifier.background(Ink.OwnerBubble)
                        else Modifier.background(Glass.Veil.copy(alpha = 0.78f)).border(1.dp, Glass.Edge, RoundedCornerShape(22.dp)),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
