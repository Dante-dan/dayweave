package app.dayweave.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dayweave.ai.DayDigest
import app.dayweave.ai.DigestState
import app.dayweave.capture.CaptureService
import app.dayweave.core.Person
import app.dayweave.core.Segment
import app.dayweave.core.SegmentStatus
import app.dayweave.graph
import app.dayweave.ui.Navigator
import app.dayweave.ui.Tab
import app.dayweave.ui.TabBarClearance
import app.dayweave.ai.perform
import app.dayweave.ui.motion.BlossomMark
import app.dayweave.ui.motion.bloomTap
import app.dayweave.ui.components.AvatarStack
import app.dayweave.ui.components.SuggestionCard
import app.dayweave.ui.components.ShimmerLines
import app.dayweave.ui.components.glass
import app.dayweave.ui.components.localDate
import app.dayweave.ui.components.pressable
import app.dayweave.ui.sharedSegment
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DigestScreen(nav: Navigator) {
    val graph = LocalContext.current.graph
    val segments by graph.archive.segments.collectAsStateWithLifecycle()
    val people by graph.voice.people.collectAsStateWithLifecycle()
    val digests by graph.digest.digests.collectAsStateWithLifecycle()
    val states by graph.digest.states.collectAsStateWithLifecycle()
    val suggestions by graph.agent.suggestions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val open = remember(suggestions) { suggestions.filter { it.status == "new" && (it.at == null || it.at > System.currentTimeMillis()) }.take(5) }
    val days = remember(segments) { segments.groupBy { localDate(it.startedAt) }.toSortedMap(compareByDescending { it }) }

    LaunchedEffect(segments) { graph.digest.refresh() }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("header") { Header(nav) }
        if (open.isNotEmpty()) {
            item("agent-title") {
                Row(Modifier.padding(start = 22.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Sparkle("拾日为你留意")
                }
            }
            items(open, key = { "s-${it.id}" }) { s ->
                SuggestionCard(
                    s = s,
                    onAction = { action ->
                        when (action) {
                            "dismiss" -> graph.agent.setStatus(s.id, "dismissed")
                            "done" -> graph.agent.setStatus(s.id, "done")
                            else -> if (perform(context, s, action)) graph.agent.setStatus(s.id, "done")
                        }
                    },
                    onSource = { nav.openConversation(s.segmentId) },
                    modifier = Modifier.animateItem().padding(horizontal = 16.dp),
                )
            }
        }
        if (days.isEmpty()) item("empty") { EmptyDigest(nav) }
        items(days.entries.toList(), key = { it.key.toString() }) { (date, list) ->
            DayCard(
                date = date,
                segments = list,
                digest = digests[date],
                state = states[date] ?: DigestState.Idle,
                people = people,
                onClick = { nav.openDay(date) },
                modifier = Modifier.animateItem().padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun Header(nav: Navigator) {
    val capture by CaptureService.state.collectAsStateWithLifecycle()
    val hour = LocalTime.now().hour
    Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val spin = rememberInfiniteTransition(label = "mark")
            val turn by spin.animateFloat(0f, 72f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "turn")
            BlossomMark(30.dp, rotation = turn)
            Spacer(Modifier.width(8.dp))
            Text("Dayweave", style = Type.Wordmark)
            Spacer(Modifier.weight(1f))
            MiniOrb(capture.running) { nav.openTab(Tab.CAPTURE) }
        }
        Text(
            when (hour) {
                in 5..10 -> "早上好"
                in 11..13 -> "中午好"
                in 14..17 -> "下午好"
                else -> "晚上好"
            } + "，这是你的拾日",
            style = Type.Display,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) + " · " + when (hour) {
                in 5..10 -> "朝花未落，今天慢慢来"
                in 11..17 -> "花开半日，记得抬头看看"
                else -> "夕拾朝花，看看今天拾起了什么"
            },
            style = Type.Caption,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 右上角的小光环：记录中缓慢旋转，点按进入采集页。 */
@Composable
private fun MiniOrb(running: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "mini")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "rot")
    Box(Modifier.size(44.dp).pressable(onClick, scaleTo = 0.86f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(44.dp).rotate(if (running) rot else 0f)) {
            if (running) drawCircle(Brush.sweepGradient(Ink.StorySweep), style = Stroke(2.5.dp.toPx()))
            else drawCircle(Glass.Veil.copy(alpha = 0.9f), style = Stroke(1.dp.toPx()))
        }
        Box(Modifier.size(36.dp).clip(CircleShape).background(if (running) Ink.Primary else Glass.FillStrong), contentAlignment = Alignment.Center) {
            Icon(
                if (running) Icons.Outlined.GraphicEq else Icons.Outlined.MicNone, if (running) "正在记录" else "开始记录",
                tint = if (running) Ink.OnPrimary else Ink.Primary, modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayCard(
    date: LocalDate,
    segments: List<Segment>,
    digest: DayDigest?,
    state: DigestState,
    people: Map<String, Person>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = segments.filter { it.status == SegmentStatus.DONE }
    val faces = done.flatMap { it.speakerIds }.distinct()

    Column(
        modifier
            .fillMaxWidth()
            .sharedSegment("day-$date")
            .bloomTap(onClick, scaleTo = 0.985f)
            .glass(RoundedCornerShape(28.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.dayweave.ui.components.dayLabel(date), style = Type.Title)
                Text("${segments.size} 段对话 · ${app.dayweave.ui.components.minutes(segments.sumOf { it.durationMs })}", style = Type.Caption)
            }
            if (faces.isNotEmpty()) AvatarStack(faces.map { people[it] }, size = 32.dp)
        }
        Spacer(Modifier.height(16.dp))

        AnimatedContent(
            targetState = Triple(digest, state, done.isEmpty()),
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) },
            contentKey = { (d, s, empty) -> "${d?.generatedAt}-${s::class.simpleName}-$empty" },
            label = "digest",
        ) { (d, s, empty) ->
            when {
                d != null -> DigestPreview(d, updating = s is DigestState.Generating)
                empty -> Hint("等这天的对话转写完成后，秘书会开始整理")
                s is DigestState.Generating -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Sparkle("秘书正在整理…")
                    ShimmerLines(3)
                }
                s is DigestState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.message, style = Type.Headline)
                    Text(
                        if (s.needsSetup) "在“采集 → 服务”里设置好之后，这里会出现每日总结。转写内容不受影响。"
                        else "稍后会自动重试，也可以进入这一天手动重新整理。",
                        style = Type.Caption,
                    )
                }
                else -> Hint("准备整理…")
            }
        }

        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("查看这一天", style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.SemiBold))
            Icon(Icons.Outlined.ChevronRight, null, tint = Ink.Primary, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DigestPreview(d: DayDigest, updating: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(updating) { Sparkle("有新对话，正在更新…") }
        Text(d.headline, style = Type.Title.copy(fontWeight = FontWeight.Bold))
        Text(d.summary, style = Type.Body.copy(color = Ink.Primary.copy(alpha = 0.82f)), maxLines = 4, overflow = TextOverflow.Ellipsis)
        val todos = d.followUps.filter { it.owner == "me" }.take(3)
        if (todos.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Glass.Veil.copy(alpha = 0.55f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("要跟进", style = Type.Micro.copy(color = Ink.Secondary))
                todos.forEach { f ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(Ink.StoryBrush))
                        Spacer(Modifier.width(10.dp))
                        Text(f.text, style = Type.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (d.topics.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                d.topics.take(5).forEachIndexed { i, topic -> TopicChip(topic, i) }
            }
        }
    }
}

private val TopicColors = listOf(Glass.Pink, Glass.Lavender, Glass.Sky, Glass.Peach, Glass.Mint, Glass.Butter)

@Composable
fun TopicChip(text: String, index: Int) {
    Text(
        "# $text",
        style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.Medium),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(TopicColors[index % TopicColors.size].copy(alpha = 0.75f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** 一朵缓慢旋转的小花，表示秘书正在工作。转速按 sin² 起伏，像呼吸，不机械。 */
@Composable
fun Sparkle(text: String) {
    val t = rememberInfiniteTransition(label = "sparkle")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    // 角度 = 72°·(φ − sin(2πφ)/2π)：一周期转过一瓣，中段快、两端慢
    val rot = 72f * (phase - kotlin.math.sin(app.dayweave.ui.motion.TAU * phase) / app.dayweave.ui.motion.TAU)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BlossomMark(16.dp, rotation = rot)
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.Caption.copy(color = Ink.Pink, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = Type.Caption)

@Composable
private fun EmptyDigest(nav: Navigator) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BlossomMark(64.dp)
        Spacer(Modifier.height(16.dp))
        Text("朝花夕拾，从第一段对话开始", style = Type.Headline)
        Text("开始记录后，秘书会在这里按天整理经历、决定和要跟进的事。", style = Type.Caption, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(18.dp))
        Text(
            "去开始记录",
            style = Type.Headline.copy(color = Ink.OnPrimary),
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.Primary).pressable({ nav.openTab(Tab.CAPTURE) })
                .padding(horizontal = 22.dp, vertical = 11.dp),
        )
    }
}
