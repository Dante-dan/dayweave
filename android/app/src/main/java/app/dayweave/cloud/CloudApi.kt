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
import app.dayweave.core.tr

@Serializable
data class LedgerEntry(val at: Long, val kind: String, val seconds: Long = 0, val micros: Long)

/** 一个授权码的额度与用量。金额单位是百万分之一美元。 */
@Serializable
data class CloudAccount(
    val grantedMicros: Long = 0,
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
    /** 还没有填授权码。 */
    data object NoLicense : CloudStatus
    data class Ready(val account: CloudAccount) : CloudStatus
    data class Failed(val message: String) : CloudStatus
}

private class NoLicenseException : IllegalStateException("no license code")

/**
 * 拾日云（商业版）：转写和整理用我们提供的密钥。没有账户体系，授权码就是凭证：
 * 每个授权码有固定额度，按“有效转录时长”扣费，用完换一个新的授权码。
 * 有效时长由服务端按收到的音频长度计算；App 在本地已经去掉了静音、环境音和播放的声音。
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
            base.isBlank() -> tr("这个版本没有接入拾日云", "This build isn't connected to Dayweave Cloud")
            settings.licenseCode.isNullOrBlank() -> tr("还没有填写拾日云授权码", "No Dayweave Cloud license code yet")
            (status.value as? CloudStatus.Ready)?.account?.let { it.balanceMicros <= 0 } == true -> tr("授权码额度已用完", "This license code is used up")
            else -> null
        }

    private fun auth(code: String? = settings.licenseCode): Map<String, String> {
        if (code.isNullOrBlank()) throw NoLicenseException()
        return mapOf("Authorization" to "Bearer ${code.trim()}")
    }

    /** 刷新额度和计价，用于“服务与用量”页面。 */
    fun refresh() {
        if (base.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching { _pricing.value = json.decodeFromString<CloudPricing>(getJson("$base/v1/pricing")) }
            if (settings.licenseCode.isNullOrBlank()) {
                _status.value = CloudStatus.NoLicense
                return@launch
            }
            if (_status.value !is CloudStatus.Ready) _status.value = CloudStatus.Loading
            runCatching { json.decodeFromString<CloudAccount>(getJson("$base/v1/me", auth())) }
                .onSuccess { _status.value = CloudStatus.Ready(it) }
                .onFailure {
                    _status.value = CloudStatus.Failed(
                        if (it is HttpException && it.code == 401) tr("授权码无效", "Invalid license code")
                        else tr("连接失败", "Couldn't connect"),
                    )
                }
        }
    }

    /** 使用一个授权码：先向服务端核对，有效才保存。成功返回 null，否则返回错误说明。 */
    fun activate(code: String): String? = try {
        val account = json.decodeFromString<CloudAccount>(getJson("$base/v1/me", auth(code)))
        settings.licenseCode = code.trim()
        _status.value = CloudStatus.Ready(account)
        null
    } catch (e: HttpException) {
        when (e.code) {
            401 -> tr("授权码无效", "Invalid license code")
            429 -> tr("尝试次数太多，请一小时后再试", "Too many attempts. Try again in an hour")
            else -> tr("验证失败（${e.code}）", "Couldn't verify (${e.code})")
        }
    } catch (e: Exception) {
        tr("网络不可用", "No network")
    }

    fun clearLicense() {
        settings.licenseCode = null
        _status.value = CloudStatus.NoLicense
    }

    override fun recognize(pcm: ShortArray, from: Int, to: Int): Recognition {
        val body = buildJsonObject { put("audio", pcmBase64(pcm, from, to)) }.toString()
        val response = postJson("$base/v1/transcribe", body, auth(), readTimeoutMs = 120_000)
        return parseRecognition(json.parseToJsonElement(response).jsonObject)
    }

    /** 每日整理或主动提醒：提示词在服务端，只发任务类型、语言和转写内容。 */
    fun assist(task: String, input: String): String {
        require(task == "digest" || task == "agent") { "Dayweave Cloud only runs digests and reminders" }
        val body = buildJsonObject {
            put("kind", task)
            put("lang", if (app.dayweave.core.Lang.english) "en" else "zh")
            put("input", input)
        }.toString()
        return json.parseToJsonElement(postJson("$base/v1/assist", body, auth(), readTimeoutMs = 180_000))
            .jsonObject.getValue("text").jsonPrimitive.content
    }
}
