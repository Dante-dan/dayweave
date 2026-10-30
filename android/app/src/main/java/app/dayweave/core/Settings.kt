package app.dayweave.core

import android.content.Context
import app.dayweave.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 用户选中的麦克风输入。蓝牙地址在 HyperOS 上会被隐藏，所以同时记设备名。 */
data class InputSelection(val type: Int, val address: String, val name: String)

/** 转写与整理由谁提供：拾日云（按有效转录时长计费）或用户自己的密钥（开源版）。 */
enum class ServiceMode { CLOUD, OWN }

/** 自带密钥时的整理模型。 */
enum class AiProvider(val label: String) {
    GEMINI("Gemini API"),
    OPENAI("OpenAI 兼容接口"),
    VERTEX("Vertex AI（用转写的服务账号）"),
}

data class AiConfig(
    val provider: AiProvider = AiProvider.GEMINI,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
) {
    val defaultModel get() = when (provider) {
        AiProvider.GEMINI, AiProvider.VERTEX -> "gemini-2.5-flash"
        AiProvider.OPENAI -> "gpt-4.1-mini"
    }
    val effectiveModel get() = model.ifBlank { defaultModel }
}

/** 是否带了拾日云的服务地址。开源构建不带，只能自带密钥。 */
val cloudAvailable get() = BuildConfig.CLOUD_URL.isNotBlank()

/** 密钥只存在 App 私有目录，不进备份、不打包、不上传到拾日云。 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _input = MutableStateFlow(readInput())
    val input: StateFlow<InputSelection?> = _input

    private val _autoBackground = MutableStateFlow(prefs.getBoolean("auto_background", true))
    /** 开始记录后自动回到桌面，之后在通知栏或快捷开关里控制。 */
    val autoBackground: StateFlow<Boolean> = _autoBackground

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

    /** 拾日云的账户令牌，首次使用时自动创建。 */
    var cloudToken: String?
        get() = prefs.getString("cloud_token", null)
        set(value) = prefs.edit().putString("cloud_token", value).apply()

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
