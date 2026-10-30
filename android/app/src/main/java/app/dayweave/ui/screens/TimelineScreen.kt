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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
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
import app.dayweave.core.Segment
import app.dayweave.core.SourceKind
import app.dayweave.graph
import app.dayweave.ui.Navigator
import app.dayweave.ui.TabBarClearance
import app.dayweave.ui.components.ConversationCard
import app.dayweave.ui.components.clock
import app.dayweave.ui.components.dayLabel
import app.dayweave.ui.components.glass
import app.dayweave.ui.components.localDate
import app.dayweave.ui.components.pressable
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type

/** 录过的声音按时间排列：每天一组，片段挂在一条竖向时间轴上。 */
@Composable
fun TimelineScreen(nav: Navigator) {
    val graph = LocalContext.current.graph
    val segments by graph.archive.segments.collectAsStateWithLifecycle()
    val people by graph.voice.people.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(segments, query, people) {
        val q = query.trim()
        if (q.isEmpty()) segments else segments.filter { s ->
            s.turns.any { it.text.contains(q, ignoreCase = true) } || s.speakerIds.any { people[it]?.name?.contains(q) == true }
        }
    }
    val days = remember(filtered) { filtered.groupBy { localDate(it.startedAt) }.toSortedMap(compareByDescending { it }) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = TabBarClearance)) {
        item("title") {
            Column(Modifier.padding(start = 20.dp, top = 14.dp, bottom = 14.dp)) {
                Text("时间线", style = Type.Display)
                Text("录下的每一段声音，和它说了什么", style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item("search") { SearchField(query) { query = it } }
        if (days.isEmpty()) {
            item("empty") {
                Text(
                    if (query.isBlank()) "还没有录音" else "没有找到“$query”",
                    style = Type.Caption, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                )
            }
        }
        days.forEach { (date, list) ->
            item("day-$date") {
                Row(Modifier.animateItem().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text(dayLabel(date), style = Type.Title)
                    Spacer(Modifier.width(8.dp))
                    Text("${list.size} 段 · ${app.dayweave.ui.components.minutes(list.sumOf { it.durationMs })}", style = Type.Caption)
                }
            }
            itemsIndexed(list, key = { _, s -> s.id }) { i, segment ->
                TimelineRow(
                    segment = segment,
                    first = i == 0,
                    last = i == list.lastIndex,
                    modifier = Modifier.animateItem(),
                ) {
                    ConversationCard(
                        segment = segment, people = people,
                        onClick = { nav.openConversation(segment.id) },
                        onRetry = { graph.pipeline.retry(segment.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(segment: Segment, first: Boolean, last: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val playback = segment.source == SourceKind.PLAYBACK
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 12.dp, end = 16.dp)) {
        Column(Modifier.width(46.dp).padding(top = 18.dp), horizontalAlignment = Alignment.End) {
            Text(clock(segment.startedAt), style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.SemiBold))
        }
        // 时间轴：麦克风是快拍渐变节点，播放声音是天蓝节点
        Canvas(Modifier.width(28.dp).fillMaxHeight()) {
            val x = size.width / 2
            val nodeY = 26.dp.toPx()
            val line = Color.White.copy(alpha = 0.9f)
            if (!first) drawLine(line, Offset(x, 0f), Offset(x, nodeY), 2.dp.toPx())
            if (!last) drawLine(line, Offset(x, nodeY), Offset(x, size.height), 2.dp.toPx())
            drawCircle(Color.White, 7.dp.toPx(), Offset(x, nodeY))
            if (playback) drawCircle(Color(0xFF6BB8F5), 4.5.dp.toPx(), Offset(x, nodeY))
            else drawCircle(Brush.linearGradient(Ink.Story, Offset(x - 5, nodeY - 5), Offset(x + 5, nodeY + 5)), 4.5.dp.toPx(), Offset(x, nodeY))
            drawCircle(Glass.Lavender, 7.dp.toPx(), Offset(x, nodeY), style = Stroke(1.dp.toPx()))
        }
        Box(Modifier.weight(1f).padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), elevation = 4.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Ink.Secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("搜索说过的话或说话人", style = Type.Body.copy(color = Ink.Tertiary))
            BasicTextField(
                query, onChange, singleLine = true,
                textStyle = Type.Body.copy(color = Ink.Primary),
                cursorBrush = SolidColor(Ink.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(query.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            Icon(Icons.Rounded.Close, "清除", tint = Ink.Secondary, modifier = Modifier.size(18.dp).pressable({ onChange("") }))
        }
    }
}
