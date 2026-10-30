package app.dayweave.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dayweave.core.OWNER_ID
import app.dayweave.core.Person
import app.dayweave.graph
import app.dayweave.mergePeople
import app.dayweave.ui.Navigator
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import app.dayweave.ui.components.Avatar
import app.dayweave.ui.motion.PetalBurst
import app.dayweave.ui.motion.Petals
import app.dayweave.ui.components.ConversationCard
import app.dayweave.ui.components.dayLabel
import app.dayweave.ui.components.glass
import app.dayweave.ui.components.localDate
import app.dayweave.ui.components.minutes
import app.dayweave.ui.components.pressable
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type

/**
 * 一个人的主页：标注名字（头像随之变成名字的第一个字），把认错的声音合并进来，
 * 按声纹归类查看和这个人的所有对话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(id: String, nav: Navigator) {
    val graph = LocalContext.current.graph
    val people by graph.voice.people.collectAsStateWithLifecycle()
    val segments by graph.archive.segments.collectAsStateWithLifecycle()
    val enrolled by graph.voice.ownerEnrolled.collectAsStateWithLifecycle()
    val person = people[id]
    val talks = remember(segments, id) { segments.filter { s -> s.turns.any { it.speakerId == id } } }
    var editing by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }

    LaunchedEffect(person) { if (person == null) nav.back() }
    if (person == null) return

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("bar") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                RoundGlassButton(nav.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "返回", modifier = Modifier.size(18.dp).padding(start = 4.dp)) }
            }
        }
        item("hero") {
            Column(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), strong = true).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 起好名字的一刻：花瓣绕头像转 3/4 圈后散开，新的首字带过冲地弹出来。
                Box(contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = person,
                        contentKey = { it.initial },
                        transitionSpec = {
                            (scaleIn(spring(dampingRatio = 0.48f, stiffness = 260f), initialScale = 0.55f) + fadeIn()) togetherWith
                                (scaleOut(tween(160), targetScale = 1.15f) + fadeOut(tween(140)))
                        },
                        label = "avatar",
                    ) { p -> Avatar(p, 96.dp, ring = true) }
                    PetalBurst(person.name, Petals.ORBIT, Modifier.size(96.dp), count = 12, durationMs = 1_300, radius = 150f)
                }
                Spacer(Modifier.size(14.dp))
                AnimatedContent(editing, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "name") { e ->
                    if (e) NameEditor(person) { name ->
                        if (name != null) graph.voice.rename(person.id, name)
                        editing = false
                    } else Text(person.name, style = Type.Display)
                }
                val total = talks.sumOf { s -> s.turns.filter { it.speakerId == id }.sumOf { it.endMs - it.startMs } }
                Text(
                    if (talks.isEmpty()) "还没有对话" else "${talks.size} 段对话 · 说了约 ${minutes(total)} · 最近 ${dayLabel(localDate(talks.first().startedAt))}",
                    style = Type.Caption, modifier = Modifier.padding(top = 6.dp),
                )
                if (!person.isOwner) {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Action(Icons.Rounded.Edit, if (person.named) "改名" else "这是谁？") { editing = true }
                        Action(Icons.Rounded.MergeType, "和别人是同一个人") { merging = true }
                    }
                    Text(
                        "名字和声纹只存在这台手机上。之后听到同样的声音，会自动认出是 ${person.name}。",
                        style = Type.Micro, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        if (talks.isNotEmpty()) {
            item("label") { Text("一起的对话", style = Type.Headline, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) }
        }
        items(talks, key = { it.id }) { s ->
            ConversationCard(
                segment = s, people = people,
                onClick = { nav.openConversation(s.id) },
                onRetry = { graph.pipeline.retry(s.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }

    if (merging) {
        val targets = people.values.filter { it.id != id && (it.id != OWNER_ID || enrolled) }
        ModalBottomSheet(
            onDismissRequest = { merging = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Glass.Base,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Text("${person.name} 其实是…", style = Type.Title, modifier = Modifier.padding(horizontal = 6.dp))
                Text("合并后，两种声音都会认作同一个人，以前的对话也会一起改过来。", style = Type.Caption, modifier = Modifier.padding(6.dp))
                Column(Modifier.padding(top = 8.dp).glass(RoundedCornerShape(24.dp), elevation = 4.dp)) {
                    targets.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().pressable({
                                merging = false
                                graph.mergePeople(id, t.id)
                                nav.back()
                                nav.openPerson(t.id)
                            }, scaleTo = 0.98f).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(t, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(t.name, style = Type.Headline)
                        }
                    }
                    if (targets.isEmpty()) Text("还没有其他人", style = Type.Caption, modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
}

@Composable
private fun NameEditor(person: Person, done: (String?) -> Unit) {
    var text by remember { mutableStateOf(if (person.named) person.name else "") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(200.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Surface)
                .border(1.dp, Ink.Hairline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (text.isEmpty()) Text("例如：妈妈、Alex、王老师", style = Type.Body.copy(color = Ink.Tertiary))
            BasicTextField(
                text, { text = it.take(20) }, singleLine = true,
                textStyle = Type.Title.copy(color = Ink.Primary),
                cursorBrush = SolidColor(Ink.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done(text.takeIf { it.isNotBlank() }) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Ink.Primary).pressable({ done(text.takeIf { it.isNotBlank() }) }),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Check, "保存", tint = Ink.OnPrimary, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun Action(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Glass.Veil.copy(alpha = 0.9f)).pressable(onClick, scaleTo = 0.94f)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Ink.Primary, modifier = Modifier.size(16.dp))
        Text(text, style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.SemiBold))
    }
}
