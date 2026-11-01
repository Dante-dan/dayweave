package app.dayweave.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import app.dayweave.core.Listening
import app.dayweave.core.SceneKind
import app.dayweave.core.Segment
import app.dayweave.ui.components.icon
import app.dayweave.ui.components.minutes
import app.dayweave.ui.components.tint
import app.dayweave.core.SourceKind
import app.dayweave.graph
import app.dayweave.ui.Navigator
import app.dayweave.ui.TabBarClearance
import app.dayweave.ui.components.ConversationCard
import app.dayweave.ui.components.PeopleRow
import app.dayweave.ui.components.clock
import app.dayweave.ui.components.dayLabel
import app.dayweave.ui.components.glass
import app.dayweave.ui.components.localDate
import app.dayweave.ui.components.pressable
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import app.dayweave.core.tr

/** 录过的声音按时间排列：每天一组，片段挂在一条竖向时间轴上。 */
@Composable
fun TimelineScreen(nav: Navigator) {
    val graph = LocalContext.current.graph
    val segments by graph.archive.segments.collectAsStateWithLifecycle()
    val listening by graph.archive.listening.collectAsStateWithLifecycle()
    val people by graph.voice.people.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(segments, query, people) {
        val q = query.trim()
        if (q.isEmpty()) segments else segments.filter { s ->
            s.turns.any { it.text.contains(q, ignoreCase = true) } || s.speakerIds.any { people[it]?.name?.contains(q) == true }
        }
    }
    val days = remember(filtered, listening, query) {
        val spans = if (query.isBlank()) listening.map(Entry::Span) else emptyList()
        (filtered.map(Entry::Talk) + spans)
            .sortedByDescending { it.at }
            .groupBy { localDate(it.at) }
            .toSortedMap(compareByDescending { it })
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = TabBarClearance)) {
        item("title") {
            Column(Modifier.padding(start = 20.dp, top = 14.dp, bottom = 14.dp)) {
                Text(tr("时间线", "Timeline"), style = Type.Display)
                Text(tr("录下的每一段声音，和它说了什么", "Every moment recorded, and what was said"), style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
            }
        }
        val faces = people.values.filter { p -> p.isOwner || (!p.ignored && segments.any { s -> p.id in s.speakerIds }) }
        if (faces.size > 1) {
            item("people") { PeopleRow(faces, { nav.openPerson(it.id) }, Modifier.padding(bottom = 14.dp)) }
        }
        item("search") { SearchField(query) { query = it } }
        if (days.isEmpty()) {
            item("empty") {
                Text(
                    if (query.isBlank()) tr("还没有录音", "Nothing recorded yet") else tr("没有找到“$query”", "Nothing found for “$query”"),
                    style = Type.Caption, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                )
            }
        }
        days.forEach { (date, list) ->
            val talks = list.filterIsInstance<Entry.Talk>().map { it.segment }
            item("day-$date") {
                Row(Modifier.animateItem().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text(dayLabel(date), style = Type.Title)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("${talks.size} 段 · ${app.dayweave.ui.components.minutes(talks.sumOf { it.durationMs })}", "${talks.size} · ${app.dayweave.ui.components.minutes(talks.sumOf { it.durationMs })}"), style = Type.Caption)
                }
            }
            itemsIndexed(list, key = { _, e -> e.key }) { i, entry ->
                TimelineRow(
                    at = entry.at,
                    node = entry.node,
                    first = i == 0,
                    last = i == list.lastIndex,
                    modifier = Modifier.animateItem(),
                ) {
                    when (entry) {
                        is Entry.Talk -> ConversationCard(
                            segment = entry.segment, people = people,
                            onClick = { nav.openConversation(entry.segment.id) },
                            onRetry = { graph.pipeline.retry(entry.segment.id) },
                        )
                        is Entry.Span -> ListeningRow(entry.listening)
                    }
                }
            }
        }
    }
}

/** 时间线上的一项：一段对话，或一段听音乐、听播客、通话的时间。 */
private sealed interface Entry {
    val at: Long
    val key: String
    /** 节点颜色：对话用快拍渐变，场景用对应的粉彩。 */
    val node: Color?

    data class Talk(val segment: Segment) : Entry {
        override val at get() = segment.startedAt
        override val key get() = segment.id
        override val node: Color? get() = if (segment.source == SourceKind.PLAYBACK) Color(0xFF6BB8F5) else null
    }

    data class Span(val listening: Listening) : Entry {
        override val at get() = listening.startedAt
        override val key get() = "l-${listening.id}"
        override val node: Color get() = listening.scene.kind.tint
    }
}

@Composable
private fun TimelineRow(at: Long, node: Color?, first: Boolean, last: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 12.dp, end = 16.dp)) {
        Column(Modifier.width(46.dp).padding(top = 18.dp), horizontalAlignment = Alignment.End) {
            Text(clock(at), style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.SemiBold))
        }
        Canvas(Modifier.width(28.dp).fillMaxHeight()) {
            val x = size.width / 2
            val nodeY = 26.dp.toPx()
            val line = Glass.Veil.copy(alpha = 0.9f)
            if (!first) drawLine(line, Offset(x, 0f), Offset(x, nodeY), 2.dp.toPx())
            if (!last) drawLine(line, Offset(x, nodeY), Offset(x, size.height), 2.dp.toPx())
            drawCircle(Glass.Veil, 7.dp.toPx(), Offset(x, nodeY))
            if (node != null) drawCircle(node, 4.5.dp.toPx(), Offset(x, nodeY))
            else drawCircle(Brush.linearGradient(Ink.Story, Offset(x - 5, nodeY - 5), Offset(x + 5, nodeY + 5)), 4.5.dp.toPx(), Offset(x, nodeY))
            drawCircle(Glass.Lavender, 7.dp.toPx(), Offset(x, nodeY), style = Stroke(1.dp.toPx()))
        }
        Box(Modifier.weight(1f).padding(vertical = 6.dp)) { content() }
    }
}

/** 听音乐、播客、视频或通话的一段时间：轻薄的一行，不抢对话卡片的视线。 */
@Composable
private fun ListeningRow(l: Listening) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).glass(RoundedCornerShape(18.dp), elevation = 0.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(l.scene.kind.tint), contentAlignment = Alignment.Center) {
            Icon(l.scene.kind.icon, null, tint = Ink.Primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "[${l.scene.kind.label}]" + (l.scene.app?.let { " $it" } ?: ""),
                style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.SemiBold),
            )
            Text(
                listOfNotNull(l.scene.title, "${clock(l.startedAt)}–${clock(l.endedAt)}", minutes(l.durationMs),
                    if (l.scene.kind == SceneKind.CALL) tr("只记录你这一侧", "your side only") else tr("未录制", "not recorded")).joinToString(" · "),
                style = Type.Micro, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), elevation = 4.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = Ink.Secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(tr("搜索说过的话或说话人", "Search what was said, or who"), style = Type.Body.copy(color = Ink.Tertiary))
            BasicTextField(
                query, onChange, singleLine = true,
                textStyle = Type.Body.copy(color = Ink.Primary),
                cursorBrush = SolidColor(Ink.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(query.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            Icon(Icons.Outlined.Close, tr("清除", "Clear"), tint = Ink.Secondary, modifier = Modifier.size(18.dp).pressable({ onChange("") }))
        }
    }
}
