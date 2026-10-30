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
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Refresh
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

/** 带来源的一条结论。 */
private data class Line(val text: String, val ids: List<String>, val tag: String? = null)

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
                RoundGlassButton(nav.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "返回", modifier = Modifier.size(18.dp).padding(start = 4.dp)) }
                Spacer(Modifier.weight(1f))
                RoundGlassButton({ graph.digest.refresh(date, force = true) }) { Icon(Icons.Rounded.Refresh, "重新整理", modifier = Modifier.size(20.dp)) }
            }
        }

        item("hero") {
            Column(
                Modifier.fillMaxWidth().sharedSegment("day-$date").glass(RoundedCornerShape(28.dp), strong = true).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${dayLabel(date)} · ${date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))}",
                    style = Type.Caption.copy(fontWeight = FontWeight.Medium),
                )
                when {
                    digest != null -> {
                        if (state is DigestState.Generating) Sparkle("正在用新的对话更新…")
                        Text(digest.headline, style = Type.Display)
                        Text(digest.summary, style = Type.Body.copy(color = Ink.Primary.copy(alpha = 0.85f)))
                        if (digest.topics.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                digest.topics.forEachIndexed { i, t -> TopicChip(t, i) }
                            }
                        }
                    }
                    state is DigestState.Generating -> {
                        Sparkle("秘书正在整理…")
                        ShimmerLines(4)
                    }
                    state is DigestState.Failed -> {
                        Text(if (state.needsSetup) "AI 整理还没有开通" else state.message, style = Type.Title)
                        Text("下面是这一天的全部对话原文。", style = Type.Caption)
                    }
                    else -> Text("这一天还没有整理", style = Type.Title)
                }
            }
        }

        if (digest != null) {
            val sections = listOf(
                "要跟进" to digest.followUps.map {
                    Line(it.text, it.segmentIds, when (it.owner) { "me" -> "我"; "other" -> "对方"; else -> "待确认" })
                },
                "做了的决定" to digest.decisions.map { Line(it.text, it.segmentIds) },
                "今天的经历" to digest.highlights.map { Line(it.text, it.segmentIds) },
                "值得留意" to digest.insights.map {
                    Line(it.text, it.segmentIds, when (it.kind) { "fact" -> "事实"; "suggestion" -> "建议"; else -> "推测" })
                },
            ).filter { it.second.isNotEmpty() }
            sections.forEachIndexed { i, (title, lines) ->
                item("s-$title") { Section(title, lines, i, byId, nav) }
            }
        }

        item("convs") {
            Text("当天的对话", style = Type.Headline, modifier = Modifier.padding(start = 6.dp, top = 12.dp))
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
                digest?.let { "由 ${it.model} 根据文字档案整理 · 与原文分开保存，可随时重新生成" } ?: "",
                style = Type.Micro,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, lines: List<Line>, order: Int, byId: Map<String, Segment>, nav: Navigator) {
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
                    Text(line.text, style = Type.Body)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        line.tag?.let { Tag(it, filled = true) }
                        line.ids.mapNotNull(byId::get).forEach { s ->
                            Tag("${clock(s.startedAt)} 原文", onClick = { nav.openConversation(s.id) })
                        }
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
        style = Type.Micro.copy(color = if (filled) Color.White else Ink.Primary, fontWeight = FontWeight.SemiBold),
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
