package app.dayweave.ai

import app.dayweave.transcribe.GoogleAuth
import app.dayweave.transcribe.HttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.net.HttpURLConnection
import java.net.URL

private const val PROJECT = "gen-lang-client-0050324770"
const val GEMINI_MODEL = "gemini-2.5-flash"
private const val ENDPOINT =
    "https://aiplatform.googleapis.com/v1/projects/$PROJECT/locations/global/publishers/google/models/$GEMINI_MODEL:generateContent"

/** Vertex AI 上的 Gemini，复用转写的服务账号。返回模型输出的 JSON 文本。 */
class GeminiClient(private val auth: GoogleAuth) {
    val available get() = auth.available

    fun generateJson(system: String, user: String, schema: JsonElement): String {
        val body = buildJsonObject {
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
        }
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${auth.token()}")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) {
            throw HttpException(conn.responseCode, conn.errorStream?.bufferedReader()?.readText().orEmpty())
        }
        val response = Json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
        return response["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!
            .jsonArray.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
    }
}
