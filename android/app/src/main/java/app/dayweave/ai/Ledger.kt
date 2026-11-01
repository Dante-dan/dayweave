package app.dayweave.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** 账本里的一笔。source：ai（记账助手从对话里听到的）或 manual（用户手记）。 */
@Serializable
data class Expense(
    val id: String,
    val date: String,
    val time: String = "",
    val amount: Double,
    val currency: String = "CNY",
    val purpose: String,
    val category: String = "other",
    val source: String = "manual",
    val segmentIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    val localDate: LocalDate get() = LocalDate.parse(date)
}

@Serializable
private data class LedgerFile(val entries: List<Expense> = emptyList(), val dismissed: Set<String> = emptySet())

/**
 * 账本：记账助手从每日整理里带来的消费，加上用户手记的。存在 App 私有目录的 ledger.json。
 * 整理会重新生成，所以按“日期 + 金额 + 币种 + 依据片段”去重；用户删掉的 AI 记录记在 dismissed 里，不会再被带回来。
 */
class Ledger(root: File) {
    private val file = File(root, "ledger.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private var data = runCatching { json.decodeFromString<LedgerFile>(file.readText()) }.getOrDefault(LedgerFile())

    private val _entries = MutableStateFlow(sorted(data.entries))
    val entries: StateFlow<List<Expense>> = _entries

    private fun sorted(list: List<Expense>) = list.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.time }.thenByDescending { it.createdAt })

    private fun key(date: String, amount: Double, currency: String, segments: List<String>) =
        "$date|${"%.2f".format(amount)}|${currency.uppercase()}|${segments.sorted().firstOrNull().orEmpty()}"

    private fun key(e: Expense) = key(e.date, e.amount, e.currency, e.segmentIds)

    @Synchronized
    private fun save(next: LedgerFile) {
        data = next
        file.writeText(json.encodeToString(LedgerFile.serializer(), next))
        _entries.value = sorted(next.entries)
    }

    /** 新整理出来后，把记账助手听到的消费并进账本。 */
    @Synchronized
    fun absorb(date: LocalDate, spoken: List<SpokenExpense>) {
        if (spoken.isEmpty()) return
        val known = data.entries.map(::key).toSet() + data.dismissed
        val added = spoken.filter { it.amount > 0 && it.purpose.isNotBlank() }.mapNotNull { s ->
            val currency = s.currency.ifBlank { "CNY" }.uppercase()
            if (key(date.toString(), s.amount, currency, s.segmentIds) in known) null
            else Expense(
                id = UUID.randomUUID().toString(), date = date.toString(), time = s.time.take(5), amount = s.amount, currency = currency,
                purpose = s.purpose, category = ExpenseCategory.of(s.category).id, source = "ai", segmentIds = s.segmentIds,
            )
        }.distinctBy(::key)
        if (added.isNotEmpty()) save(data.copy(entries = data.entries + added))
    }

    @Synchronized
    fun upsert(expense: Expense) = save(data.copy(entries = data.entries.filterNot { it.id == expense.id } + expense))

    @Synchronized
    fun delete(id: String) {
        val e = data.entries.firstOrNull { it.id == id } ?: return
        save(data.copy(entries = data.entries - e, dismissed = if (e.source == "ai") data.dismissed + key(e) else data.dismissed))
    }

    @Synchronized
    fun clear() = save(LedgerFile())

    /** 给助手对话用的账本摘要：最近的若干笔。 */
    fun brief(limit: Int = 40): String = _entries.value.take(limit).joinToString("\n") {
        "- ${it.date} ${it.time} ${"%.2f".format(it.amount)} ${it.currency} ${ExpenseCategory.of(it.category).label} ${it.purpose}"
    }
}
