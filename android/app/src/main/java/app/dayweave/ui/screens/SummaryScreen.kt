package app.dayweave.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.ReceiptLong
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dayweave.ai.Advice
import app.dayweave.ai.Assistant
import app.dayweave.ai.ChatThread
import app.dayweave.ai.Expense
import app.dayweave.ai.ExpenseCategory
import app.dayweave.ai.GENERAL_ASSISTANT
import app.dayweave.ai.assistantLabel
import app.dayweave.core.tr
import app.dayweave.graph
import app.dayweave.ui.Navigator
import app.dayweave.ui.TabBarClearance
import app.dayweave.ui.components.Avatar
import app.dayweave.ui.components.assistantIcon
import app.dayweave.ui.components.dayLabel
import app.dayweave.ui.components.glass
import app.dayweave.ui.components.pressable
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

private fun money(amount: Double, currency: String) = when (currency.uppercase()) {
    "CNY", "RMB" -> "¥%.2f".format(amount)
    "USD" -> "$%.2f".format(amount)
    "EUR" -> "€%.2f".format(amount)
    "JPY" -> "¥%.0f".format(amount)
    else -> "%.2f %s".format(amount, currency)
}

private fun totals(list: List<Expense>) = list.groupBy { it.currency }.map { (c, l) -> money(l.sumOf { it.amount }, c) }.joinToString(" · ")

fun assistantIconFor(id: String): ImageVector = Assistant.of(id)?.let(::assistantIcon) ?: Icons.Outlined.ChatBubbleOutline

// ---------------------------------------------------------------- 总结

/** 底栏“总结”：把日子按人、按助手、按话题归类；账本和助手对话的入口。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryScreen(nav: Navigator) {
    val graph = LocalContext.current.graph
    val digests by graph.digest.digests.collectAsStateWithLifecycle()
    val enabled by graph.settings.assistants.collectAsStateWithLifecycle()
    val people by graph.voice.people.collectAsStateWithLifecycle()
    val segments by graph.archive.segments.collectAsStateWithLifecycle()
    val ledger by graph.ledger.entries.collectAsStateWithLifecycle()
    val threads by graph.chat.threads.collectAsStateWithLifecycle()

    val adviceBy = remember(digests) { digests.values.flatMap { it.advice }.groupBy { it.assistant } }
    val assistants = remember(enabled, adviceBy) {
        Assistant.entries.filter { it in enabled || adviceBy[it.id].orEmpty().isNotEmpty() }
    }
    val talkers = remember(segments, people) {
        segments.flatMap { s -> s.turns.map { it.speakerId to s.id } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (id, segs) -> people[id]?.takeIf { !it.ignored }?.let { Triple(it, segs.distinct().size, segs.size) } }
            .sortedByDescending { it.third }
    }
    val topics = remember(digests) { digests.values.flatMap { it.topics }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(16) }
    val month = YearMonth.now()
    val monthSpend = remember(ledger) { ledger.filter { YearMonth.from(it.localDate) == month } }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("title") {
            Column(Modifier.padding(start = 4.dp, top = 14.dp)) {
                Text(tr("总结", "Summary"), style = Type.Display)
                Text(tr("按人、按助手、按话题归类你的日子，也可以直接问问助手", "Your days sorted by people, assistants and topics. Ask any assistant directly"), style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
            }
        }

        if (Assistant.BOOKKEEPING in enabled || ledger.isNotEmpty()) item("ledger") {
            Row(
                Modifier.fillMaxWidth().pressable(nav.openLedger, scaleTo = 0.98f).glass(RoundedCornerShape(28.dp), strong = true).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(Glass.Peach.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ReceiptLong, null, tint = Ink.Primary)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("账本", "Ledger"), style = Type.Title)
                    Text(
                        if (monthSpend.isEmpty()) tr("本月还没有记账 · 聊到花钱时记账助手会记下来", "Nothing this month yet · the bookkeeper notes spending you talk about")
                        else tr("本月 ${monthSpend.size} 笔 · ${totals(monthSpend)}", "${monthSpend.size} this month · ${totals(monthSpend)}"),
                        style = Type.Caption,
                    )
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = Ink.Tertiary)
            }
        }

        item("assistants-title") { SectionTitle(tr("随身助手", "Assistants")) }
        item("assistants") {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp))) {
                AssistantRow(Icons.Outlined.ChatBubbleOutline, tr("拾日秘书", "Dayweave"), tr("问问这些天的任何事", "Ask about anything from your days")) {
                    nav.startChat(GENERAL_ASSISTANT, "", "")
                }
                assistants.forEach { a ->
                    val list = adviceBy[a.id].orEmpty()
                    val sub = when {
                        a == Assistant.BOOKKEEPING -> tr("已记 ${ledger.count { it.source == "ai" }} 笔", "${ledger.count { it.source == "ai" }} entries noted")
                        list.isEmpty() -> if (a in enabled) tr("还没有出场 · ${a.note}", "Not yet · ${a.note}") else tr("已关闭", "Off")
                        else -> tr("共 ${list.size} 条建议 · 最近：${list.last().title.ifBlank { list.last().text }}", "${list.size} suggestions · latest: ${list.last().title.ifBlank { list.last().text }}")
                    }
                    Hair()
                    AssistantRow(assistantIcon(a), a.label, sub, count = if (a == Assistant.BOOKKEEPING) null else list.size) { nav.openAssistant(a.id) }
                }
            }
        }

        if (talkers.isNotEmpty()) {
            item("people-title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle(tr("人", "People")) }
                    Text(
                        tr("声纹整理 · ${people.values.count { !it.isOwner && !it.ignored }}", "Tidy voices · ${people.values.count { !it.isOwner && !it.ignored }}"),
                        style = Type.Micro.copy(color = Ink.Primary, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Glass.Veil.copy(alpha = 0.8f)).pressable(nav.openVoices, scaleTo = 0.94f).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            item("people") {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp))) {
                    talkers.take(12).forEachIndexed { i, (p, convs, lines) ->
                        if (i > 0) Hair()
                        Row(
                            Modifier.fillMaxWidth().pressable({ nav.openPerson(p.id) }, scaleTo = 0.98f).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(p, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = Type.Headline)
                                Text(tr("$convs 段对话 · $lines 句", "$convs conversations · $lines lines"), style = Type.Caption)
                            }
                            Icon(Icons.Outlined.ChevronRight, null, tint = Ink.Tertiary)
                        }
                    }
                }
            }
        }

        if (topics.isNotEmpty()) {
            item("topics-title") { SectionTitle(tr("常聊的话题", "Recurring topics")) }
            item("topics") {
                FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    topics.forEachIndexed { i, (t, n) -> TopicChip(if (n > 1) "$t · $n" else t, i) }
                }
            }
        }

        val recent = threads.values.sortedByDescending { it.updatedAt }.take(6)
        if (recent.isNotEmpty()) {
            item("chats-title") { SectionTitle(tr("最近的对话", "Recent chats")) }
            item("chats") { ThreadList(recent, nav) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = Type.Micro.copy(color = Ink.Secondary), modifier = Modifier.padding(start = 8.dp, top = 4.dp))

@Composable
private fun Hair() = Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(Glass.Veil.copy(alpha = 0.9f)))

@Composable
private fun AssistantRow(icon: ImageVector, title: String, subtitle: String, count: Int? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick, scaleTo = 0.98f).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Glass.Veil.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Ink.Primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Headline)
            Text(subtitle, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (count != null && count > 0) {
            Text(
                "$count", style = Type.Micro.copy(color = Ink.OnPrimary, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.Primary).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Ink.Tertiary)
    }
}

@Composable
private fun ThreadList(list: List<ChatThread>, nav: Navigator) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp))) {
        list.forEachIndexed { i, t ->
            if (i > 0) Hair()
            AssistantRow(
                assistantIconFor(t.assistant),
                t.title.ifBlank { tr("新的对话", "New chat") },
                "${assistantLabel(t.assistant)} · ${t.messages.size} ${tr("条", "messages")}",
            ) { nav.openChat(t.id) }
        }
    }
}

@Composable
private fun TopBar(nav: Navigator, title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundGlassButton(nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, tr("返回", "Back"), modifier = Modifier.size(18.dp).padding(start = 4.dp)) }
        Text(title, style = Type.Headline, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun PillButton(text: String, icon: ImageVector, filled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (filled) Ink.Primary else Glass.Veil.copy(alpha = 0.85f))
            .pressable(onClick, scaleTo = 0.94f).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (filled) Ink.OnPrimary else Ink.Primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.Caption.copy(color = if (filled) Ink.OnPrimary else Ink.Primary, fontWeight = FontWeight.SemiBold))
    }
}

// ---------------------------------------------------------------- 单个助手

/** 一位助手的全部建议（按天），可以直接问它，或引用某一条来问。 */
@Composable
fun AssistantScreen(id: String, nav: Navigator) {
    val graph = LocalContext.current.graph
    val assistant = Assistant.of(id)
    val digests by graph.digest.digests.collectAsStateWithLifecycle()
    val threads by graph.chat.threads.collectAsStateWithLifecycle()
    val enabled by graph.settings.assistants.collectAsStateWithLifecycle()
    val byDay = remember(digests, id) {
        digests.entries.sortedByDescending { it.key }.mapNotNull { (d, digest) -> digest.advice.filter { it.assistant == id }.takeIf { it.isNotEmpty() }?.let { d to it } }
    }
    val mine = remember(threads, id) { threads.values.filter { it.assistant == id }.sortedByDescending { it.updatedAt } }
    val label = assistantLabel(id)

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("bar") { TopBar(nav, "") }
        item("hero") {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), strong = true).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Glass.Pink.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Icon(assistantIconFor(id), null, tint = Ink.Primary, modifier = Modifier.size(26.dp))
                }
                Text(label, style = Type.Display)
                assistant?.let { Text(it.note, style = Type.Caption) }
                if (assistant != null && assistant !in enabled) {
                    Text(tr("这个助手已关闭，可以在“采集 → 随身助手”里打开", "This assistant is off. Turn it on in Capture → Assistants"), style = Type.Micro.copy(color = Ink.Danger))
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(tr("和它聊聊", "Ask $label"), Icons.Outlined.ChatBubbleOutline) { nav.startChat(id, "", "") }
                    if (assistant == Assistant.BOOKKEEPING) PillButton(tr("打开账本", "Open ledger"), Icons.Outlined.ReceiptLong, filled = false, onClick = nav.openLedger)
                }
            }
        }
        if (mine.isNotEmpty()) {
            item("threads-title") { SectionTitle(tr("和它的对话", "Chats")) }
            item("threads") { ThreadList(mine.take(8), nav) }
        }
        if (byDay.isNotEmpty()) {
            item("advice-title") { SectionTitle(tr("共 ${byDay.sumOf { it.second.size }} 条建议", "${byDay.sumOf { it.second.size }} suggestions")) }
            items(byDay, key = { it.first.toString() }) { (date, list) -> AdviceDay(date, list, nav) }
        } else if (assistant != Assistant.BOOKKEEPING) {
            item("empty") {
                Text(
                    tr("还没有出场。对话里聊到相关的事时，它会在当天的整理里给出建议。", "Nothing yet. When your conversations touch its area, it adds suggestions to that day's digest."),
                    style = Type.Caption, modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun AdviceDay(date: LocalDate, list: List<Advice>, nav: Navigator) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(dayLabel(date), style = Type.Micro.copy(color = Ink.Secondary), modifier = Modifier.pressable({ nav.openDay(date) }))
        list.forEach { a ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (a.title.isNotBlank()) Text(a.title, style = Type.Headline)
                Text(a.text, style = Type.Body)
                if (a.basis.isNotBlank()) Text(tr("依据：${a.basis}", "Source: ${a.basis}"), style = Type.Micro.copy(color = Ink.Secondary))
                QuoteAsk { nav.startChat(a.assistant, a.title.ifBlank { "" }.let { if (it.isBlank()) a.text else "$it：${a.text}" }, "${date} ${assistantLabel(a.assistant)}") }
            }
        }
    }
}

/** 小小的“引用提问”按钮。 */
@Composable
fun QuoteAsk(onClick: () -> Unit) {
    Row(
        Modifier.padding(top = 2.dp).clip(RoundedCornerShape(50)).background(Glass.Lavender.copy(alpha = 0.7f)).pressable(onClick, scaleTo = 0.9f)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FormatQuote, null, tint = Ink.Primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(tr("引用提问", "Ask about this"), style = Type.Micro.copy(color = Ink.Primary, fontWeight = FontWeight.SemiBold))
    }
}

// ---------------------------------------------------------------- 账本

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerScreen(nav: Navigator) {
    val graph = LocalContext.current.graph
    val entries by graph.ledger.entries.collectAsStateWithLifecycle()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    val shown = remember(entries, month) { entries.filter { YearMonth.from(it.localDate) == month } }
    val byCategory = remember(shown) {
        shown.groupBy { it.category }.map { (c, l) -> ExpenseCategory.of(c) to l }.sortedByDescending { (_, l) -> l.sumOf { it.amount } }
    }
    val max = byCategory.maxOfOrNull { (_, l) -> l.sumOf { it.amount } } ?: 1.0

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("bar") {
            TopBar(nav, tr("账本", "Ledger")) {
                RoundGlassButton({ editing = blankExpense() }) { Icon(Icons.Outlined.Add, tr("记一笔", "Add"), modifier = Modifier.size(22.dp)) }
            }
        }
        item("month") {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), strong = true).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ChevronLeft, tr("上个月", "Previous month"), modifier = Modifier.pressable({ month = month.minusMonths(1) }).padding(4.dp))
                    Text(tr("${month.year} 年 ${month.monthValue} 月", "${month.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${month.year}"), style = Type.Headline, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Icon(Icons.Outlined.ChevronRight, tr("下个月", "Next month"), tint = if (month < YearMonth.now()) Ink.Primary else Ink.Tertiary,
                        modifier = Modifier.pressable({ if (month < YearMonth.now()) month = month.plusMonths(1) }).padding(4.dp))
                }
                Text(if (shown.isEmpty()) tr("这个月还没有记录", "Nothing this month") else totals(shown), style = Type.Display)
                Text(tr("${shown.size} 笔 · 其中 ${shown.count { it.source == "ai" }} 笔由记账助手从对话里记下", "${shown.size} entries · ${shown.count { it.source == "ai" }} noted from conversations"), style = Type.Caption)
                byCategory.forEach { (c, l) ->
                    val f by animateFloatAsState((l.sumOf { it.amount } / max).toFloat(), spring(dampingRatio = 0.8f), label = "bar")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row { Text(c.label, style = Type.Caption, modifier = Modifier.weight(1f)); Text(totals(l), style = Type.Caption.copy(color = Ink.Primary)) }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Glass.Veil.copy(alpha = 0.7f))) {
                            Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(50)).background(Ink.StoryBrush))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(tr("记一笔", "Add"), Icons.Outlined.Add) { editing = blankExpense() }
                    PillButton(tr("问记账助手", "Ask the bookkeeper"), Icons.Outlined.ChatBubbleOutline, filled = false) {
                        nav.startChat(Assistant.BOOKKEEPING.id, if (shown.isEmpty()) "" else tr("${month} 共 ${shown.size} 笔：${totals(shown)}", "$month: ${shown.size} entries, ${totals(shown)}"), tr("账本", "Ledger"))
                    }
                }
            }
        }
        items(shown, key = { it.id }) { e ->
            Row(
                Modifier.fillMaxWidth().animateItem().glass(RoundedCornerShape(22.dp)).pressable({ editing = e }, scaleTo = 0.98f).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(e.purpose, style = Type.Headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(dayLabel(e.localDate) + if (e.time.isNotBlank()) " ${e.time}" else "", ExpenseCategory.of(e.category).label, if (e.source == "ai") tr("对话里听到", "From a conversation") else tr("手记", "Manual"))
                            .joinToString(" · "),
                        style = Type.Caption,
                    )
                    if (e.segmentIds.isNotEmpty()) Text(tr("查看原话", "See the source"), style = Type.Micro.copy(color = Ink.Accent, fontWeight = FontWeight.SemiBold), modifier = Modifier.pressable({ nav.openConversation(e.segmentIds.first()) }))
                }
                Text(money(e.amount, e.currency), style = Type.Title)
            }
        }
    }
    editing?.let { e -> ExpenseSheet(e, onDismiss = { editing = null }) }
}

private fun blankExpense() = Expense(id = UUID.randomUUID().toString(), date = LocalDate.now().toString(), amount = 0.0, purpose = "", currency = if (app.dayweave.core.Lang.english) "USD" else "CNY")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ExpenseSheet(initial: Expense, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val exists = graph.ledger.entries.value.any { it.id == initial.id }
    var amount by remember { mutableStateOf(if (initial.amount > 0) "%.2f".format(initial.amount).trimEnd('0').trimEnd('.') else "") }
    var currency by remember { mutableStateOf(initial.currency) }
    var purpose by remember { mutableStateOf(initial.purpose) }
    var category by remember { mutableStateOf(initial.category) }
    var date by remember { mutableStateOf(initial.localDate) }
    var armed by remember { mutableStateOf(false) }
    val valid = (amount.toDoubleOrNull() ?: 0.0) > 0 && purpose.isNotBlank()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Glass.Base,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 22.dp).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (exists) tr("修改这一笔", "Edit entry") else tr("记一笔", "New entry"), style = Type.Title)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, tr("金额", "Amount"), Modifier.weight(1f), KeyboardType.Decimal)
                listOf("CNY", "USD").forEach { c -> Choice(c, currency == c) { currency = c } }
            }
            Field(purpose, { purpose = it.take(80) }, tr("买了什么、为了什么", "What it was for"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExpenseCategory.entries.forEach { c -> Choice(c.label, category == c.id) { category = c.id } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChevronLeft, tr("前一天", "Previous day"), modifier = Modifier.pressable({ date = date.minusDays(1) }).padding(4.dp))
                Text(dayLabel(date) + " · $date", style = Type.Body, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Icon(Icons.Outlined.ChevronRight, tr("后一天", "Next day"), tint = if (date < LocalDate.now()) Ink.Primary else Ink.Tertiary,
                    modifier = Modifier.pressable({ if (date < LocalDate.now()) date = date.plusDays(1) }).padding(4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.alphaIf(!valid)) {
                    PillButton(tr("保存", "Save"), Icons.Outlined.Add) {
                        if (!valid) return@PillButton
                        graph.ledger.upsert(initial.copy(amount = amount.toDouble(), currency = currency, purpose = purpose.trim(), category = category, date = date.toString()))
                        onDismiss()
                    }
                }
                if (exists) PillButton(if (armed) tr("再点一次删除", "Tap again to delete") else tr("删除", "Delete"), Icons.Outlined.DeleteOutline, filled = false) {
                    if (armed) { graph.ledger.delete(initial.id); onDismiss() } else armed = true
                }
            }
        }
    }
}

private fun Modifier.alphaIf(dim: Boolean) = graphicsLayer { alpha = if (dim) 0.4f else 1f }

/** 选中是实心，不是描边。 */
@Composable
internal fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ink.Primary else Glass.Veil.copy(alpha = 0.8f), label = "choice")
    Text(
        text, style = Type.Caption.copy(color = if (selected) Ink.OnPrimary else Ink.Primary, fontWeight = FontWeight.Medium),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).pressable(onClick, scaleTo = 0.92f).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier.fillMaxWidth(), keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Glass.Veil.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(hint, style = Type.Body.copy(color = Ink.Tertiary))
        BasicTextField(
            value, onChange, singleLine = singleLine, maxLines = if (singleLine) 1 else 5,
            textStyle = Type.Body.copy(color = Ink.Primary), cursorBrush = SolidColor(Ink.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------- 对话

@Composable
fun ChatScreen(id: String, nav: Navigator) {
    val graph = LocalContext.current.graph
    val threads by graph.chat.threads.collectAsStateWithLifecycle()
    val busy by graph.chat.busy.collectAsStateWithLifecycle()
    val errors by graph.chat.errors.collectAsStateWithLifecycle()
    val thread = threads[id]
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(thread == null) { if (thread == null) nav.back() }
    if (thread == null) return
    val thinking = id in busy
    LaunchedEffect(thread.messages.size, thinking) { list.animateScrollToItem(maxOf(0, thread.messages.size + 2)) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            TopBar(nav, assistantLabel(thread.assistant)) {
                RoundGlassButton({ graph.chat.delete(id) }) { Icon(Icons.Outlined.DeleteOutline, tr("删除对话", "Delete chat"), modifier = Modifier.size(20.dp)) }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("intro") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(46.dp).clip(CircleShape).background(Glass.Pink.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                        Icon(assistantIconFor(thread.assistant), null, tint = Ink.Primary)
                    }
                    Text(
                        tr("${assistantLabel(thread.assistant)}会参考你最近的整理和账本回答。建议仅供参考。", "${assistantLabel(thread.assistant)} answers with your recent digests and ledger in mind. For reference only."),
                        style = Type.Micro, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            if (thread.quote.isNotBlank()) item("quote") {
                Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(14.dp)) {
                    Box(Modifier.width(3.dp).height(40.dp).clip(RoundedCornerShape(50)).background(Ink.StoryBrush))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        if (thread.quoteFrom.isNotBlank()) Text(thread.quoteFrom, style = Type.Micro.copy(color = Ink.Secondary))
                        Text(thread.quote, style = Type.Body, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            items(thread.messages, key = { "${it.at}-${it.role}" }) { m -> Bubble(m.role == "user", m.text, m.basis) }
            item("status") {
                AnimatedVisibility(thinking) { Sparkle(tr("正在想…", "Thinking…")) }
                errors[id]?.let { Text(it, style = Type.Caption.copy(color = Ink.Danger)) }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
            Field(draft, { draft = it.take(2000) }, if (thread.quote.isNotBlank()) tr("关于这段，想问什么？", "What would you like to ask about this?") else tr("问点什么…", "Ask something…"),
                Modifier.weight(1f), singleLine = false)
            Spacer(Modifier.width(10.dp))
            val can = draft.isNotBlank() && !thinking
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (can) Ink.Primary else Glass.Veil).pressable({
                    if (can) { graph.chat.send(id, draft); draft = "" }
                }, scaleTo = 0.88f),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Outlined.Send, tr("发送", "Send"), tint = if (can) Ink.OnPrimary else Ink.Tertiary, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun Bubble(mine: Boolean, text: String, basis: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp))
                .background(if (mine) Ink.Primary else Glass.FillStrong)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text, style = Type.Body.copy(color = if (mine) Ink.OnPrimary else Ink.Primary))
            if (basis.isNotBlank()) Text(tr("依据：$basis", "Source: $basis"), style = Type.Micro.copy(color = Ink.Secondary))
        }
    }
}
