package app.dayweave.core

import android.content.Context
import app.dayweave.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 用户选中的麦克风输入。蓝牙地址在 HyperOS 上会被隐藏，所以同时记设备名。 */
data class InputSelection(val type: Int, val address: String, val name: String)

/** 转写与整理由谁提供：拾日云（按有效转录时长计费）或用户自己的密钥（源码版）。 */
enum class ServiceMode { CLOUD, OWN }

/** 自带密钥时的整理模型。 */
enum class AiProvider(private val zh: String, private val en: String) {
    GEMINI("Gemini API", "Gemini API"),
    OPENAI("OpenAI 兼容接口", "OpenAI-compatible"),
    VERTEX("Vertex AI（用转写的服务账号）", "Vertex AI (same service account)");

    val label get() = tr(zh, en)
}

data class AiConfig(
    val provider: AiProvider = AiProvider.GEMINI,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
) {
    val defaultModel get() = when (provider) {
        AiProvider.GEMINI, AiProvider.VERTEX -> "gemini-3.6-flash"
        AiProvider.OPENAI -> "gpt-4.1-mini"
    }
    val effectiveModel get() = model.ifBlank { defaultModel }
}

/** 是否带了拾日云的服务地址。源码版构建不带，只能自带密钥。 */
val cloudAvailable get() = BuildConfig.CLOUD_URL.isNotBlank()

/** 密钥只存在 App 私有目录，不进备份、不打包、不上传到拾日云。 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _input = MutableStateFlow(readInput())
    val input: StateFlow<InputSelection?> = _input

    private val _autoBackground = MutableStateFlow(prefs.getBoolean("auto_background", true))
    /** 开始记录后自动回到桌面，之后在通知栏或快捷开关里控制。 */
    val autoBackground: StateFlow<Boolean> = _autoBackground

    private val _standby = MutableStateFlow(prefs.getBoolean("standby_notification", true))
    /** 没在记录时，在通知栏留一个“开始”按钮。 */
    val standby: StateFlow<Boolean> = _standby

    fun setStandby(enabled: Boolean) {
        prefs.edit().putBoolean("standby_notification", enabled).apply()
        _standby.value = enabled
    }

    private val _assistants = MutableStateFlow(
        prefs.getStringSet("assistants", null)?.mapNotNull(app.dayweave.ai.Assistant::of)?.toSet()
            ?: app.dayweave.ai.Assistant.defaults,
    )
    /** 打开的随身助手。默认全开，只在对话涉及时才出场。 */
    val assistants: StateFlow<Set<app.dayweave.ai.Assistant>> = _assistants

    fun setAssistant(assistant: app.dayweave.ai.Assistant, enabled: Boolean) {
        val next = if (enabled) _assistants.value + assistant else _assistants.value - assistant
        prefs.edit().putStringSet("assistants", next.map { it.id }.toSet()).apply()
        _assistants.value = next
    }

    private val _theme = MutableStateFlow(
        prefs.getString("theme", null)?.let { runCatching { app.dayweave.ui.theme.ThemeMode.valueOf(it) }.getOrNull() }
            ?: app.dayweave.ui.theme.ThemeMode.SYSTEM,
    )
    /** 外观：跟随系统、浅色（朝）、深色（夕）。 */
    val theme: StateFlow<app.dayweave.ui.theme.ThemeMode> = _theme

    fun setTheme(mode: app.dayweave.ui.theme.ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
        _theme.value = mode
    }

    private val _language = MutableStateFlow(
        prefs.getString("language", null)?.let { runCatching { Language.valueOf(it) }.getOrNull() } ?: Language.SYSTEM,
    )
    /** 界面语言。也决定 AI 整理和提醒用什么语言写。 */
    val language: StateFlow<Language> = _language

    fun setLanguage(language: Language) {
        prefs.edit().putString("language", language.name).apply()
        _language.value = language
        Lang.english = Lang.resolve(language)
    }

    private val _mode = MutableStateFlow(
        prefs.getString("service_mode", null)?.let(ServiceMode::valueOf)
            ?: if (cloudAvailable) ServiceMode.CLOUD else ServiceMode.OWN,
    )
    val mode: StateFlow<ServiceMode> = _mode

    private val _ai = MutableStateFlow(
        AiConfig(
            provider = prefs.getString("ai_provider", null)?.let(AiProvider::valueOf) ?: AiProvider.GEMINI,
            apiKey = prefs.getString("ai_key", "").orEmpty(),
            baseUrl = prefs.getString("ai_base_url", "").orEmpty(),
            model = prefs.getString("ai_model", "").orEmpty(),
        ),
    )
    val ai: StateFlow<AiConfig> = _ai

    /** 本次安装的随机标识，拾日云据此统计一个授权码在几台设备上使用。重装后会变。 */
    val installId: String by lazy {
        prefs.getString("install_id", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("install_id", it).apply() }
    }

    /** 拾日云授权码：凭证本身，只存在本机。 */
    var licenseCode: String?
        get() = prefs.getString("license_code", null)
        set(value) = prefs.edit().putString("license_code", value).remove("cloud_token").apply()

    private fun readInput(): InputSelection? {
        val name = prefs.getString("input_name", null) ?: return null
        return InputSelection(prefs.getInt("input_type", 0), prefs.getString("input_address", "").orEmpty(), name)
    }

    fun setInput(selection: InputSelection) {
        prefs.edit()
            .putInt("input_type", selection.type)
            .putString("input_address", selection.address)
            .putString("input_name", selection.name)
            .apply()
        _input.value = selection
    }

    fun setAutoBackground(enabled: Boolean) {
        prefs.edit().putBoolean("auto_background", enabled).apply()
        _autoBackground.value = enabled
    }

    fun setMode(mode: ServiceMode) {
        prefs.edit().putString("service_mode", mode.name).apply()
        _mode.value = if (mode == ServiceMode.CLOUD && !cloudAvailable) ServiceMode.OWN else mode
    }

    fun setAi(config: AiConfig) {
        prefs.edit()
            .putString("ai_provider", config.provider.name)
            .putString("ai_key", config.apiKey.trim())
            .putString("ai_base_url", config.baseUrl.trim())
            .putString("ai_model", config.model.trim())
            .apply()
        _ai.value = config
    }
}
