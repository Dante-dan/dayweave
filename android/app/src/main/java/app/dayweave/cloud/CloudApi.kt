package app.dayweave.cloud

import app.dayweave.BuildConfig
import app.dayweave.core.Settings
import app.dayweave.transcribe.HttpException
import app.dayweave.transcribe.Recognition
import app.dayweave.transcribe.SpeechEngine
import app.dayweave.transcribe.getJson
import app.dayweave.transcribe.parseRecognition
import app.dayweave.transcribe.pcmBase64
import app.dayweave.transcribe.postJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class LedgerEntry(val at: Long, val kind: String, val seconds: Long = 0, val micros: Long, val note: String = "")

/** 拾日云的账户：金额单位是百万分之一美元。 */
@Serializable
data class CloudAccount(
    val balanceMicros: Long,
    val priceMicrosPerMinute: Long,
    val monthSeconds: Long = 0,
    val monthMicros: Long = 0,
    val ledger: List<LedgerEntry> = emptyList(),
) {
    val minutesLeft get() = if (priceMicrosPerMinute > 0) balanceMicros / priceMicrosPerMinute else 0
}

/** 计价方式，由服务端给出，App 只负责展示。 */
@Serializable
data class CloudPricing(
    val priceMicrosPerMinute: Long,
    val note: String = "",
)

sealed interface CloudStatus {
    data object Unknown : CloudStatus
    data object Loading : CloudStatus
    data class Ready(val account: CloudAccount) : CloudStatus
    data class Failed(val message: String) : CloudStatus
}

/**
 * 拾日云（商业版）：转写和整理用我们提供的密钥，按“有效转录时长”计费。
 * 有效时长由服务端按收到的音频长度计算；App 在本地已经去掉了静音、环境音和播放的声音。
 * 首次使用时自动创建一个匿名账户，令牌存在本机。
 */
class CloudApi(private val settings: Settings, private val scope: CoroutineScope) : SpeechEngine {
    private val base = BuildConfig.CLOUD_URL.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow<CloudStatus>(CloudStatus.Unknown)
    val status: StateFlow<CloudStatus> = _status

    private val _pricing = MutableStateFlow<CloudPricing?>(null)
    val pricing: StateFlow<CloudPricing?> = _pricing

    override val problem: String?
        get() = when {
            base.isBlank() -> "这个版本没有接入拾日云"
            (status.value as? CloudStatus.Ready)?.account?.let { it.balanceMicros <= 0 } == true -> "拾日云余额不足"
            else -> null
        }

    @Synchronized
    private fun token(): String {
        settings.cloudToken?.let { return it }
        val created = json.parseToJsonElement(postJson("$base/v1/accounts", "{}")).jsonObject
        val token = created.getValue("token").jsonPrimitive.content
        settings.cloudToken = token
        return token
    }

    private fun auth() = mapOf("Authorization" to "Bearer ${token()}")

    /** 令牌失效（例如换了服务器）时重新建一个账户再试一次。 */
    private fun <T> authed(block: (Map<String, String>) -> T): T = try {
        block(auth())
    } catch (e: HttpException) {
        if (e.code != 401) throw e
        settings.cloudToken = null
        block(auth())
    }

    /** 刷新账户和计价，用于“服务与用量”页面。 */
    fun refresh() {
        if (base.isBlank()) return
        scope.launch(Dispatchers.IO) {
            if (_status.value !is CloudStatus.Ready) _status.value = CloudStatus.Loading
            runCatching {
                _pricing.value = json.decodeFromString<CloudPricing>(getJson("$base/v1/pricing"))
                json.decodeFromString<CloudAccount>(authed { getJson("$base/v1/me", it) })
            }.onSuccess { _status.value = CloudStatus.Ready(it) }
                .onFailure { _status.value = CloudStatus.Failed(it.message ?: "连接失败") }
        }
    }

    /** 兑换充值码。成功返回 null，否则返回错误说明。 */
    fun redeem(code: String): String? = try {
        authed { postJson("$base/v1/redeem", buildJsonObject { put("code", code.trim()) }.toString(), it) }
        refresh()
        null
    } catch (e: HttpException) {
        when (e.code) {
            404 -> "兑换码无效"
            409 -> "兑换码已经用过了"
            else -> "兑换失败（${e.code}）"
        }
    } catch (e: Exception) {
        "网络不可用"
    }

    override fun recognize(pcm: ShortArray, from: Int, to: Int): Recognition {
        val body = buildJsonObject { put("audio", pcmBase64(pcm, from, to)) }.toString()
        val response = authed { postJson("$base/v1/transcribe", body, it, readTimeoutMs = 120_000) }
        return parseRecognition(json.parseToJsonElement(response).jsonObject)
    }

    fun generateJson(system: String, user: String, schema: JsonElement): String {
        val body = buildJsonObject {
            put("system", system)
            put("user", user)
            put("schema", schema)
        }.toString()
        return json.parseToJsonElement(authed { postJson("$base/v1/digest", body, it, readTimeoutMs = 180_000) })
            .jsonObject.getValue("text").jsonPrimitive.content
    }
}
