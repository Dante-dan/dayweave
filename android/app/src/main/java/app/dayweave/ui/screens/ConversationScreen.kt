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
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.dayweave.ui.components.glass
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
import app.dayweave.core.tr
import app.dayweave.core.deviceLabel

@Composable
fun ConversationScreen(id: String, nav: Navigator) {
    val graph = LocalContext.current.graph
    val segments by graph.archive.segments.collectAsStateWithLifecycle()
    val people by graph.voice.people.collectAsStateWithLifecycle()
    val segment = segments.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    // 正在改说话人的那一句（下标）
    var relabel by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().sharedSegment(id).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundGlassButton(nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, tr("返回", "Back"), modifier = Modifier.size(18.dp).padding(start = 4.dp)) }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                if (segment != null) {
                    Text("${dayLabel(localDate(segment.startedAt))} ${clock(segment.startedAt)}", style = Type.Headline)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SourceIcon(segment)
                        Text(
                            "${if (segment.source == SourceKind.PLAYBACK) tr("播放声音", "Playback") else deviceLabel(segment.device)} · ${duration(segment.durationMs)}",
                            style = Type.Caption,
                        )
                    }
                }
            }
            RoundGlassButton({ confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, tr("删除", "Delete"), tint = Ink.Primary, modifier = Modifier.size(20.dp)) }
        }

        if (segment == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr("这段对话已删除", "This conversation was deleted"), style = Type.Caption) }
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
                                    p == null -> tr("未识别", "Unknown")
                                    p.isOwner || p.named -> p.name
                                    else -> tr("${p.name} · 标注", "${p.name} · name")
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
                        SceneChip(scene, prefix = tr("这段对话发生在${scene.kind.doing.removeSuffix("时")}", "Happened ${scene.kind.doing}"))
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
                    onClick = { relabel = index },
                )
            }
            item("footer") {
                Text(
                    buildString {
                        append(tr("点一句话可以改成别人说的\n", "Tap a line to change who said it\n"))
                        val langs = segment.languages.map { if (it.startsWith("cmn")) tr("中文", "Chinese") else if (it.startsWith("en")) "English" else it }
                        if (langs.isNotEmpty()) append(langs.joinToString(" · ")).append("\n")
                        if (segment.speechMs > 0) append(tr("有效人声 ${duration(segment.speechMs)}，静音与环境音未上传\n", "${duration(segment.speechMs)} of voice; silence and room noise were never uploaded\n"))
                        append(tr("音频已在转写后删除，只保留文字", "The audio was deleted after transcription. Only the text is kept"))
                    },
                    style = Type.Micro,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                )
            }
        }
    }

    relabel?.let { i -> RelabelSheet(id, i, people) { relabel = null } }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("删除这段对话？", "Delete this conversation?"), style = Type.Title) },
            text = { Text(tr("文字档案会从这台手机上永久删除。", "The transcript will be permanently removed from this phone."), style = Type.Body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    nav.back()
                    graph.archive.delete(id)
                }) { Text(tr("删除", "Delete"), color = Ink.Danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("取消", "Cancel"), color = Ink.Primary) } },
            containerColor = Ink.Surface,
        )
    }
}

/**
 * 手动关联说话人：这一句（或这段里这个人说的所有话）其实是谁说的。只改文字档案，不动声纹。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelabelSheet(segmentId: String, index: Int, people: Map<String, Person>, onDone: () -> Unit) {
    val graph = LocalContext.current.graph
    val segment = graph.archive.get(segmentId)
    val turn = segment?.turns?.getOrNull(index)
    if (segment == null || turn == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val current = people[turn.speakerId]
    val sameInSegment = segment.turns.indices.filter { segment.turns[it].speakerId == turn.speakerId }.toSet()
    var all by remember { mutableStateOf(false) }
    // 这段里出现的人排前面，其次是说话多的人；路人放最后
    val choices = remember(people, segment) {
        val here = segment.speakerIds.toSet()
        people.values.filter { it.id != turn.speakerId }
            .sortedWith(compareByDescending<Person> { it.isOwner }.thenByDescending { it.id in here }.thenBy { it.ignored }.thenByDescending { it.named })
    }
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Glass.Base,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(tr("这句话是谁说的？", "Who said this?"), style = Type.Title, modifier = Modifier.padding(horizontal = 6.dp))
            Text("“${turn.text.take(60)}”", style = Type.Caption, maxLines = 2, modifier = Modifier.padding(6.dp))
            if (sameInSegment.size > 1) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(tr("只改这一句", "Just this line"), !all) { all = false }
                    Choice(tr("这段里${current?.name ?: tr("未识别", "Unknown")}的 ${sameInSegment.size} 句都改", "All ${sameInSegment.size} lines by ${current?.name ?: "Unknown"} here"), all) { all = true }
                }
            }
            Column(Modifier.padding(top = 8.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()).glass(RoundedCornerShape(24.dp), elevation = 4.dp)) {
                (choices.map { it.id to it } + if (turn.speakerId != app.dayweave.core.UNKNOWN_ID) listOf(app.dayweave.core.UNKNOWN_ID to null) else emptyList()).forEach { (pid, p) ->
                    Row(
                        Modifier.fillMaxWidth().pressable({
                            graph.archive.relabelTurns(segmentId, if (all) sameInSegment else setOf(index), pid)
                            onDone()
                        }, scaleTo = 0.98f).padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(p, 36.dp, ring = false)
                        Spacer(Modifier.size(12.dp))
                        Text(p?.name ?: tr("未识别", "Unknown"), style = Type.Headline, modifier = Modifier.weight(1f))
                        if (p?.ignored == true) Text(tr("路人", "Passer-by"), style = Type.Micro)
                    }
                }
            }
            Text(
                tr("只改这段文字记录；声纹不变。想让以后也认对，可以在这个人的主页里“和别人是同一个人”。", "Only this transcript changes, not the voiceprint. To fix future recognition, use “Same person as…” on their page."),
                style = Type.Micro, modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }
    }
}

/** 聊天气泡：主人在右侧用渐变，其他人在左侧用浅灰。依次淡入上浮。 */
@Composable
private fun Bubble(turn: Turn, person: Person?, showName: Boolean, showTime: Boolean, segmentStart: Long, index: Int, onClick: () -> Unit) {
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
            Text(person?.name ?: tr("未识别", "Unknown"), style = Type.Micro.copy(color = Ink.Secondary), modifier = Modifier.padding(start = 44.dp, bottom = 3.dp))
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
                    .pressable(onClick, scaleTo = 0.97f)
                    .then(
                        if (mine) Modifier.background(Ink.OwnerBubble)
                        else Modifier.background(Glass.Veil.copy(alpha = 0.78f)).border(1.dp, Glass.Edge, RoundedCornerShape(22.dp)),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
