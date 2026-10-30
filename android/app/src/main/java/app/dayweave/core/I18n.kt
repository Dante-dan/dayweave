package app.dayweave.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 界面语言：跟随系统、中文、English。 */
enum class Language(private val zh: String, private val en: String) {
    SYSTEM("跟随系统", "System"),
    ZH("中文", "中文"),
    EN("English", "English");

    val label get() = tr(zh, en)
}

/**
 * 当前是否使用英文。它是可观察状态：Compose 读到它的地方会在切换时重组，
 * 通知、错误提示、AI 提示词这些非界面代码也直接读它。
 */
object Lang {
    var english by mutableStateOf(false)

    fun resolve(language: Language) = when (language) {
        Language.SYSTEM -> Locale.getDefault().language != "zh"
        Language.ZH -> false
        Language.EN -> true
    }

    val locale: Locale get() = if (english) Locale.ENGLISH else Locale.CHINA

    /** 写给 AI 的输出语言要求，接在系统提示词后面。 */
    val outputRule: String
        get() = if (english) {
            "\nWrite every field in natural English. Keep names, product names and quoted original words in their original language."
        } else {
            ""
        }
}

/** 中英双语文案：按当前语言取其一。 */
fun tr(zh: String, en: String) = if (Lang.english) en else zh

/** 档案里以中文保存的设备名，显示时按语言翻译。 */
fun deviceLabel(device: String): String = when (device) {
    "手机麦克风" -> tr("手机麦克风", "Phone mic")
    "通话录音" -> tr("通话录音", "Call recording")
    "测试样本 · TTS 合成" -> tr("测试样本 · TTS 合成", "Test sample · TTS")
    else -> device
}
