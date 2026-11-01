package app.dayweave.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dayweave.core.Person
import app.dayweave.core.Segment
import app.dayweave.core.tr
import app.dayweave.forgetVoices
import app.dayweave.graph
import app.dayweave.mergeGroups
import app.dayweave.ui.Navigator
import app.dayweave.ui.components.Avatar
import app.dayweave.ui.components.glass
import app.dayweave.ui.components.minutes
import app.dayweave.ui.components.pressable
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import app.dayweave.voice.TIDY_LOOSE
import app.dayweave.voice.TIDY_STRICT
import app.dayweave.voice.VoiceGroup
import app.dayweave.voice.VoiceTidy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 声纹整理：同一个人常因为距离、情绪、耳机不同被认成好几个“说话人”。
 * 这里把很像的声纹分好组，配上每个声纹说过的一句话，用户确认后一键合并；只出现过一两次的零散声纹可以归为未识别。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VoicesScreen(nav: Navigator) {
    val graph = LocalContext.current.graph
    val people by graph.voice.people.collectAsStateWithLifecycle()
    val segments by graph.archive.segments.collectAsStateWithLifecycle()
    var strict by rememberSaveable { mutableStateOf(false) }
    // 每次合并或拒绝后加一，重新计算建议
    var round by remember { mutableIntStateOf(0) }
    var confirmAll by remember { mutableStateOf(false) }
    var confirmFragments by remember { mutableStateOf(false) }

    val talk = remember(segments) { talkStats(segments) }
    val tidy by produceState<VoiceTidy?>(null, people, talk, strict, round) {
        value = withContext(Dispatchers.Default) { graph.voice.tidy(talk.ms, if (strict) TIDY_STRICT else TIDY_LOOSE) }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("bar") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                RoundGlassButton(nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, tr("返回", "Back"), modifier = Modifier.size(18.dp).padding(start = 4.dp)) }
            }
        }
        item("hero") {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), strong = true).padding(22.dp)) {
                Text(tr("声纹整理", "Tidy voices"), style = Type.Display)
                val t = tidy
                Text(
                    if (t == null) tr("正在比对声纹…", "Comparing voices…")
                    else tr(
                        "共 ${t.total} 个声纹 · ${t.groups.size} 组可能是同一个人 · ${t.fragments.size} 个零散声纹",
                        "${t.total} voices · ${t.groups.size} groups may be the same person · ${t.fragments.size} stray voices",
                    ),
                    style = Type.Caption, modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    tr(
                        "同一个人离得远近、情绪、换耳机时声音会变，容易被认成好几个人。看看每组说过的话，确认后合并；以前的对话会一起改过来。全部在手机上完成。",
                        "Distance, mood or a different headset can make one person sound like several. Check what each voice said, then merge; past conversations update too. Everything happens on this phone.",
                    ),
                    style = Type.Micro, modifier = Modifier.padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("归组", "Grouping"), style = Type.Caption)
                    Choice(tr("宽松", "Loose"), !strict) { strict = false; confirmAll = false }
                    Choice(tr("严格", "Strict"), strict) { strict = true; confirmAll = false }
                }
                val groups = tidy?.groups.orEmpty()
                AnimatedVisibility(groups.size > 1) {
                    Row(Modifier.padding(top = 14.dp)) {
                        Solid(
                            if (confirmAll) tr("再点一次，合并全部 ${groups.size} 组", "Tap again to merge all ${groups.size} groups")
                            else tr("全部合并（${groups.size} 组）", "Merge all (${groups.size} groups)"),
                        ) {
                            if (!confirmAll) confirmAll = true
                            else {
                                graph.mergeGroups(groups)
                                confirmAll = false
                                round++
                            }
                        }
                    }
                }
            }
        }

        val t = tidy
        if (t != null && t.groups.isEmpty() && t.fragments.isEmpty()) {
            item("clean") {
                Text(
                    tr("声纹已经很整齐了。", "Voices are already tidy."),
                    style = Type.Body, modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }

        if (t != null && t.fragments.isNotEmpty()) item("fragments") {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp)) {
                Text(tr("零散声纹", "Stray voices"), style = Type.Title)
                Text(
                    tr(
                        "${t.fragments.size} 个声纹只出现过一两次、加起来不到几秒，多半是路人、电视或认错的短句。归为未识别后，文字保留，只是不再算作一个人。",
                        "${t.fragments.size} voices appeared once or twice for a few seconds, likely passers-by, TV or misheard short lines. Marking them unknown keeps the text but stops counting them as people.",
                    ),
                    style = Type.Caption, modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    t.fragments.take(14).forEach { Avatar(people[it], 30.dp) }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Soft(tr("都设为路人", "Mark as passers-by")) {
                        graph.voice.setIgnored(t.fragments, true)
                        round++
                    }
                    Solid(if (confirmFragments) tr("再点一次确认", "Tap again to confirm") else tr("归为未识别", "Mark as unknown")) {
                        if (!confirmFragments) confirmFragments = true
                        else {
                            graph.forgetVoices(t.fragments)
                            confirmFragments = false
                            round++
                        }
                    }
                }
            }
        }

        val hidden = people.values.filter { it.ignored }
        if (hidden.isNotEmpty()) item("hidden") {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp)) {
                Text(tr("路人 · ${hidden.size}", "Passers-by · ${hidden.size}"), style = Type.Title)
                Text(
                    tr("不出现在总结、人物列表和每日整理里；再听到也认得出。点一下恢复显示。", "Hidden from Summary, people lists and digests, but still recognized. Tap to show again."),
                    style = Type.Caption, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hidden.forEach { p ->
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Glass.Veil.copy(alpha = 0.9f)).pressable({ graph.voice.setIgnored(p.id, false) }, scaleTo = 0.94f).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(p, 26.dp, ring = false)
                            Spacer(Modifier.width(6.dp))
                            Text(p.name, style = Type.Caption.copy(color = Ink.Primary))
                        }
                    }
                }
            }
        }

        if (t != null) items(t.groups, key = { it.members.joinToString() }) { g ->
            GroupCard(g, people, talk, onOpen = nav.openPerson,
                onMerge = { graph.mergeGroups(listOf(g)); round++ },
                onSplit = { g.members.drop(1).forEach { graph.voice.markDistinct(g.keep, it) }; round++ },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun GroupCard(
    group: VoiceGroup,
    people: Map<String, Person>,
    talk: TalkStats,
    onOpen: (String) -> Unit,
    onMerge: () -> Unit,
    onSplit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keep = people[group.keep]
    val total = group.members.sumOf { talk.ms[it] ?: 0L }
    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(vertical = 8.dp)) {
        Text(
            tr("可能都是 ${keep?.name ?: "同一个人"} · ${group.members.size} 个声纹 · 共说了约 ${minutes(total)}", "Probably all ${keep?.name ?: "one person"} · ${group.members.size} voices · about ${minutes(total)}"),
            style = Type.Caption.copy(color = Ink.Secondary), modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
        group.members.take(6).forEachIndexed { i, id ->
            val p = people[id]
            Row(
                Modifier.fillMaxWidth().pressable({ onOpen(id) }, scaleTo = 0.98f).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (p?.name ?: id) + if (i == 0) tr(" · 保留", " · keep") else "",
                        style = Type.Headline.copy(fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Medium),
                    )
                    val line = talk.sample[id]
                    if (line != null) Text("“$line”", style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(minutes(talk.ms[id] ?: 0L), style = Type.Micro)
            }
        }
        if (group.members.size > 6) Text(tr("还有 ${group.members.size - 6} 个", "${group.members.size - 6} more"), style = Type.Micro, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Solid(tr("是同一个人，合并", "Same person, merge"), onMerge)
            Soft(tr("不是", "Not the same"), onSplit)
        }
    }
}

@Composable
private fun Solid(text: String, onClick: () -> Unit) = Text(
    text, style = Type.Caption.copy(color = Ink.OnPrimary, fontWeight = FontWeight.SemiBold),
    modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.Primary).pressable(onClick, scaleTo = 0.94f).padding(horizontal = 16.dp, vertical = 9.dp),
)

@Composable
private fun Soft(text: String, onClick: () -> Unit) = Box(
    Modifier.clip(RoundedCornerShape(50)).background(Glass.Veil.copy(alpha = 0.9f)).pressable(onClick, scaleTo = 0.94f).padding(horizontal = 16.dp, vertical = 9.dp),
) { Text(text, style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.SemiBold)) }

/** 每个人说话的总时长，和一句有代表性的话（最长的那句），帮用户判断是不是同一个人。 */
class TalkStats(val ms: Map<String, Long>, val sample: Map<String, String>)

private fun talkStats(segments: List<Segment>): TalkStats {
    val ms = mutableMapOf<String, Long>()
    val best = mutableMapOf<String, String>()
    for (s in segments) for (t in s.turns) {
        ms[t.speakerId] = (ms[t.speakerId] ?: 0L) + (t.endMs - t.startMs)
        if (t.text.length > (best[t.speakerId]?.length ?: 0)) best[t.speakerId] = t.text
    }
    return TalkStats(ms, best.mapValues { it.value.take(40) })
}
