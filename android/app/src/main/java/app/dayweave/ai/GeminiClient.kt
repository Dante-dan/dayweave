package app.dayweave.ai

import app.dayweave.cloud.CloudApi
import app.dayweave.core.AiConfig
import app.dayweave.core.AiProvider
import app.dayweave.transcribe.GoogleAuth
import app.dayweave.transcribe.postJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 一种整理模型：给系统提示、用户内容和输出结构，返回 JSON 文本。 */
interface DigestModel {
    val name: String
    /** 可以用的时候为 null，否则是给用户看的原因。 */
    val problem: String?
    fun generateJson(system: String, user: String, schema: JsonElement): String
}

private fun geminiBody(system: String, user: String, schema: JsonElement) = buildJsonObject {
    putJsonObject("systemInstruction") {
        putJsonArray("parts") { addJsonObject { put("text", system) } }
    }
    putJsonArray("contents") {
        addJsonObject {
            put("role", "user")
            putJsonArray("parts") { addJsonObject { put("text", user) } }
        }
    }
    putJsonObject("generationConfig") {
        put("temperature", 0.3)
        put("responseMimeType", "application/json")
        put("responseSchema", schema)
    }
}.toString()

private fun geminiText(response: String): String =
    Json.parseToJsonElement(response).jsonObject["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!
        .jsonArray.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }

/** Gemini API（Google AI Studio 的 API 密钥）。 */
class GeminiApi(private val config: AiConfig) : DigestModel {
    override val name get() = config.effectiveModel
    override val problem get() = if (config.apiKey.isBlank()) "还没有填写 Gemini API 密钥" else null

    override fun generateJson(system: String, user: String, schema: JsonElement): String = geminiText(
        postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/${config.effectiveModel}:generateContent",
            geminiBody(system, user, schema),
            mapOf("x-goog-api-key" to config.apiKey),
            readTimeoutMs = 180_000,
        ),
    )
}

/** Vertex AI 上的 Gemini，复用转写的服务账号。 */
class VertexGemini(private val auth: GoogleAuth, private val config: AiConfig) : DigestModel {
    override val name get() = config.effectiveModel
    override val problem get() = if (!auth.available) "还没有导入 Google Cloud 服务账号" else null

    override fun generateJson(system: String, user: String, schema: JsonElement): String = geminiText(
        postJson(
            "https://aiplatform.googleapis.com/v1/projects/${auth.projectId}/locations/global/publishers/google/models/${config.effectiveModel}:generateContent",
            geminiBody(system, user, schema),
            mapOf("Authorization" to "Bearer ${auth.token()}"),
            readTimeoutMs = 180_000,
        ),
    )
}

/** OpenAI 兼容的 Chat Completions 接口：OpenAI、DeepSeek、通义、Kimi、OpenRouter、本地模型等。 */
class OpenAiCompatible(private val config: AiConfig) : DigestModel {
    override val name get() = config.effectiveModel
    override val problem get() = when {
        config.apiKey.isBlank() -> "还没有填写 API 密钥"
        else -> null
    }

    override fun generateJson(system: String, user: String, schema: JsonElement): String {
        val base = config.baseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        val body = buildJsonObject {
            put("model", config.effectiveModel)
            put("temperature", 0.3)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject { put("role", "user"); put("content", user) }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "day_digest")
                    put("schema", toJsonSchema(schema))
                }
            }
        }.toString()
        val response = postJson("$base/chat/completions", body, mapOf("Authorization" to "Bearer ${config.apiKey}"), readTimeoutMs = 180_000)
        return Json.parseToJsonElement(response).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!
            .jsonObject["content"]!!.jsonPrimitive.content
    }
}

/** 拾日云提供的整理，费用已包含在有效转录时长里。 */
class CloudDigest(private val cloud: CloudApi) : DigestModel {
    override val name = "拾日云"
    override val problem get() = cloud.problem
    override fun generateJson(system: String, user: String, schema: JsonElement) = cloud.generateJson(system, user, schema)
}

fun digestModel(config: AiConfig, auth: GoogleAuth): DigestModel = when (config.provider) {
    AiProvider.GEMINI -> GeminiApi(config)
    AiProvider.OPENAI -> OpenAiCompatible(config)
    AiProvider.VERTEX -> VertexGemini(auth, config)
}

/** Gemini 的结构描述用大写类型名，标准 JSON Schema 用小写。 */
private fun toJsonSchema(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (k == "type" && v is JsonPrimitive) JsonPrimitive(v.content.lowercase()) else toJsonSchema(v) })
    is JsonArray -> JsonArray(e.map(::toJsonSchema))
    else -> e
}
