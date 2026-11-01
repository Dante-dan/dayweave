package app.dayweave.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dayweave.ai.DigestState
import app.dayweave.core.Segment
import app.dayweave.graph
import app.dayweave.ui.Navigator
import app.dayweave.ui.components.ConversationCard
import app.dayweave.ui.components.ShimmerLines
import app.dayweave.ui.components.clock
import app.dayweave.ui.components.dayLabel
import app.dayweave.ui.components.glass
import app.dayweave.ui.components.localDate
import app.dayweave.ui.components.pressable
import app.dayweave.ui.sharedSegment
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.dayweave.core.tr

/** 带来源的一条结论。 */
private data class Line(val text: String, val ids: List<String>, val tag: String? = null, val title: String? = null, val note: String? = null, val assistant: String = app.dayweave.ai.GENERAL_ASSISTANT)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayScreen(date: LocalDate, nav: Navigator) {
    val graph = LocalContext.current.graph
    val segments by graph.archive.segments.collectAsStateWithLifecycle()
    val people by graph.voice.people.collectAsStateWithLifecycle()
    val digests by graph.digest.digests.collectAsStateWithLifecycle()
    val states by graph.digest.states.collectAsStateWithLifecycle()
    val daySegments = remember(segments, date) { segments.filter { localDate(it.startedAt) == date }.sortedBy { it.startedAt } }
    val byId = remember(daySegments) { daySegments.associateBy { it.id } }
    val digest = digests[date]
    val state = states[date] ?: DigestState.Idle

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("bar") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundGlassButton(nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, tr("返回", "Back"), modifier = Modifier.size(18.dp).padding(start = 4.dp)) }
                Spacer(Modifier.weight(1f))
                RoundGlassButton({ graph.digest.refresh(date, force = true) }) { Icon(Icons.Outlined.Refresh, tr("重新整理", "Rewrite"), modifier = Modifier.size(20.dp)) }
            }
        }

        item("hero") {
            Column(
                Modifier.fillMaxWidth().sharedSegment("day-$date").glass(RoundedCornerShape(28.dp), strong = true).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${dayLabel(date)} · ${app.dayweave.ui.components.longDate(date)}",
                    style = Type.Caption.copy(fontWeight = FontWeight.Medium),
                )
                when {
                    digest != null -> {
                        if (state is DigestState.Generating) Sparkle(tr("正在用新的对话更新…", "Updating with new conversations…"))
                        Text(digest.headline, style = Type.Display)
                        Text(digest.summary, style = Type.Body.copy(color = Ink.Primary.copy(alpha = 0.85f)))
                        if (digest.topics.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                digest.topics.forEachIndexed { i, t -> TopicChip(t, i) }
                            }
                        }
                    }
                    state is DigestState.Generating -> {
                        Sparkle(tr("秘书正在整理…", "Writing your day…"))
                        ShimmerLines(4)
                    }
                    state is DigestState.Failed -> {
                        Text(state.message, style = Type.Title)
                        Text(tr("下面是这一天的全部对话原文。", "Below are all of this day's conversations."), style = Type.Caption)
                    }
                    else -> Text(tr("这一天还没有整理", "No digest for this day yet"), style = Type.Title)
                }
            }
        }

        if (digest != null) {
            val sections = listOf(
                tr("要跟进", "Follow up") to digest.followUps.map {
                    Line(it.text, it.segmentIds, when (it.owner) { "me" -> tr("我", "Me"); "other" -> tr("对方", "Them"); else -> tr("待确认", "Unclear") })
                },
                tr("随身助手", "Assistants") to digest.advice.mapNotNull { a ->
                    val who = app.dayweave.ai.Assistant.of(a.assistant) ?: return@mapNotNull null
                    Line(a.text, a.segmentIds, who.label, title = a.title.ifBlank { null }, note = a.basis.ifBlank { null }?.let { tr("依据：$it", "Source: $it") }, assistant = a.assistant)
                },
                tr("做了的决定", "Decisions") to digest.decisions.map { Line(it.text, it.segmentIds) },
                tr("今天的经历", "What happened") to digest.highlights.map { Line(it.text, it.segmentIds) },
                tr("值得留意", "Worth noticing") to digest.insights.map {
                    Line(it.text, it.segmentIds, when (it.kind) { "fact" -> tr("事实", "Fact"); "suggestion" -> tr("建议", "Suggestion"); else -> tr("推测", "Inference") })
                },
            ).filter { it.second.isNotEmpty() }
            sections.forEachIndexed { i, (title, lines) ->
                item("s-$title") { Section(title, lines, i, byId, nav, "$date $title") }
            }
        }

        item("convs") {
            Text(tr("当天的对话", "Conversations"), style = Type.Headline, modifier = Modifier.padding(start = 6.dp, top = 12.dp))
        }
        items(daySegments, key = { it.id }) { s ->
            ConversationCard(
                segment = s, people = people,
                onClick = { nav.openConversation(s.id) },
                onRetry = { graph.pipeline.retry(s.id) },
                modifier = Modifier.animateItem(),
            )
        }
        item("note") {
            Text(
                digest?.let {
                    tr(
                        "由 ${it.model.ifBlank { "AI" }} 根据文字档案整理 · 与原文分开保存，可随时重新生成",
                        "Written by ${it.model.ifBlank { "AI" }} from your transcripts · kept apart from them, can be rewritten any time",
                    )
                } ?: "",
                style = Type.Micro,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, lines: List<Line>, order: Int, byId: Map<String, Segment>, nav: Navigator, from: String) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(140L + order * 80L)
        appear.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * 28.dp.toPx() }
            .glass(RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, style = Type.Micro.copy(color = Ink.Secondary))
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(Ink.StoryBrush))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    line.title?.let { Text(it, style = Type.Headline) }
                    Text(line.text, style = Type.Body)
                    line.note?.let { Text(it, style = Type.Micro.copy(color = Ink.Secondary)) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        line.tag?.let { Tag(it, filled = true) }
                        line.ids.mapNotNull(byId::get).forEach { s ->
                            Tag(tr("${clock(s.startedAt)} 原文", "${clock(s.startedAt)} source"), onClick = { nav.openConversation(s.id) })
                        }
                        Tag(tr("引用提问", "Ask"), onClick = { nav.startChat(line.assistant, listOfNotNull(line.title, line.text).joinToString("：") , from) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = Type.Micro.copy(color = if (filled) Ink.OnPrimary else Ink.Primary, fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) Ink.Primary else Glass.Lavender.copy(alpha = 0.7f))
            .then(if (onClick != null) Modifier.pressable(onClick, scaleTo = 0.9f) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun RoundGlassButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).pressable(onClick, scaleTo = 0.86f).glass(CircleShape, elevation = 6.dp, strong = true),
        contentAlignment = Alignment.Center,
    ) { content() }
}
