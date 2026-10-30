package app.dayweave.transcribe

import android.util.Base64
import app.dayweave.core.SAMPLE_RATE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val PROJECT = "gen-lang-client-0050324770"
private const val ENDPOINT =
    "https://us-speech.googleapis.com/v2/projects/$PROJECT/locations/us/recognizers/_:recognize"

/** Chirp 3 识别出的一个词，时间相对请求音频开头。 */
data class Word(val startMs: Long, val endMs: Long, val text: String, val label: String)

data class Recognition(val words: List<Word>, val languages: List<String>)

/** Google Speech-to-Text v2 Chirp 3 同步识别：中英双语、说话人分段、词级时间戳。单次请求不超过 60 秒。 */
class ChirpClient(private val auth: GoogleAuth) {
    val available get() = auth.available

    fun recognize(pcm: ShortArray, from: Int, to: Int): Recognition {
        val bytes = ByteBuffer.allocate((to - from) * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until to) bytes.putShort(pcm[i])
        val body = buildJsonObject {
            putJsonObject("config") {
                putJsonObject("explicitDecodingConfig") {
                    put("encoding", "LINEAR16")
                    put("sampleRateHertz", SAMPLE_RATE)
                    put("audioChannelCount", 1)
                }
                put("model", "chirp_3")
                putJsonArray("languageCodes") {
                    add("cmn-Hans-CN")
                    add("en-US")
                }
                putJsonObject("features") {
                    put("enableAutomaticPunctuation", true)
                    put("enableWordTimeOffsets", true)
                    putJsonObject("diarizationConfig") {}
                }
            }
            put("content", Base64.encodeToString(bytes.array(), Base64.NO_WRAP))
        }

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${auth.token()}")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) {
            throw HttpException(conn.responseCode, conn.errorStream?.bufferedReader()?.readText().orEmpty())
        }
        return parse(Json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject)
    }

    private fun parse(response: JsonObject): Recognition {
        val words = mutableListOf<Word>()
        val languages = mutableListOf<String>()
        for (result in (response["results"] as? JsonArray).orEmpty()) {
            val obj = result.jsonObject
            obj["languageCode"]?.jsonPrimitive?.contentOrNull?.let(languages::add)
            val alt = obj["alternatives"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
            for (w in (alt["words"] as? JsonArray).orEmpty()) {
                val word = w.jsonObject
                words += Word(
                    startMs = offsetMs(word["startOffset"]?.jsonPrimitive?.contentOrNull),
                    endMs = offsetMs(word["endOffset"]?.jsonPrimitive?.contentOrNull),
                    text = word["word"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    label = word["speakerLabel"]?.jsonPrimitive?.contentOrNull ?: "0",
                )
            }
        }
        return Recognition(words, languages.distinct())
    }

    /** "1.240s" -> 1240 */
    private fun offsetMs(value: String?): Long =
        value?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
}
