package app.dayweave.ai

import app.dayweave.core.tr

/**
 * 随身助手：每个助手是一个可以单独开关的模块，每日整理时按需出场。
 * 当天的对话真的涉及某个领域（孩子发烧、睡不好、和家人闹别扭、花了钱……），对应的助手才给有依据的建议；没涉及就安静。
 * id 与提示词、输出结构里的 assistant 枚举一致。记账助手不写建议，而是记进账本（DayDigest.expenses）。
 * defaultOn = false 的是衍生模块，默认关闭，用户按需打开。
 */
enum class Assistant(
    val id: String,
    private val zh: String,
    private val en: String,
    private val zhNote: String,
    private val enNote: String,
    val defaultOn: Boolean = true,
) {
    PARENTING("parenting", "育儿助手", "Parenting", "循证育儿，依据美国儿科学会（AAP）", "Evidence-based, following the American Academy of Pediatrics"),
    HEALTH("health", "健康助手", "Health", "依据梅奥诊所、WHO、柳叶刀等权威指南", "Following Mayo Clinic, WHO, The Lancet and similar guidance"),
    RELATIONSHIP("relationship", "人际助手", "Relationships", "亲密关系与家庭关系，有研究支持的沟通方法", "Partners and family, with research-backed ways to talk"),
    FINANCE("finance", "理财助手", "Money", "通用理财原则，不荐股、不择时", "General principles; no stock picks or timing"),
    LIFE("life", "生活助手", "Life hacks", "和眼前麻烦有关的生活小妙招", "Small practical fixes for what came up"),
    GROWTH("growth", "人生体验助手", "Living well", "让日子更有意思、更有收获", "Making days richer and more rewarding"),
    BOOKKEEPING("bookkeeping", "记账助手", "Bookkeeping", "听到花了钱就记进账本：多少、买了什么、为了什么", "Notes what you spent, on what and why, into your ledger"),
    WORK("work", "工作助手", "Work notes", "会议与工作讨论的结论、分工和下一步", "Conclusions, owners and next steps from work talk", defaultOn = false),
    LEARNING("learning", "学习助手", "Learning", "把聊到的陌生概念、书和术语讲清楚", "Explains concepts, books and terms that came up", defaultOn = false),
    OCCASIONS("occasions", "纪念日助手", "Occasions", "记住家人朋友的生日、纪念日和心愿，给送礼灵感", "Remembers birthdays, anniversaries and wishes; gift ideas", defaultOn = false),
    SHOPPING("shopping", "购物清单", "Shopping list", "说到要买、缺了、用完了的东西，汇总成清单", "Collects things to buy, run out of or replace", defaultOn = false);

    val label get() = tr(zh, en)
    val note get() = tr(zhNote, enNote)

    /** 是否会在整理里写建议。记账助手只记账。 */
    val advises get() = this != BOOKKEEPING

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
        val defaults get() = entries.filter { it.defaultOn }.toSet()
    }
}

/** 写在整理输入开头，告诉模型用户打开了哪些助手。 */
fun assistantsLine(enabled: Set<Assistant>): String =
    "启用的助手：" + if (enabled.isEmpty()) "无" else Assistant.entries.filter { it in enabled }.joinToString("、") { it.id }

/** 账本分类，与提示词里的 category 枚举一致。 */
enum class ExpenseCategory(val id: String, private val zh: String, private val en: String) {
    FOOD("food", "餐饮", "Food"),
    TRANSPORT("transport", "交通", "Transport"),
    SHOPPING("shopping", "购物", "Shopping"),
    HOUSING("housing", "居家", "Home"),
    HEALTH("health", "医疗健康", "Health"),
    KIDS("kids", "孩子", "Kids"),
    EDUCATION("education", "学习", "Education"),
    ENTERTAINMENT("entertainment", "娱乐", "Fun"),
    SOCIAL("social", "人情", "Social"),
    TRAVEL("travel", "旅行", "Travel"),
    OTHER("other", "其他", "Other");

    val label get() = tr(zh, en)

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: OTHER
    }
}
