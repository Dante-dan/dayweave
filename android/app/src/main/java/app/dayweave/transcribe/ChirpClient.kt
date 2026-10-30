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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Chirp 3 识别出的一个词，时间相对请求音频开头。 */
data class Word(val startMs: Long, val endMs: Long, val text: String, val label: String)

data class Recognition(val words: List<Word>, val languages: List<String>)

/** 一种转写服务。单次请求不超过 60 秒音频。 */
interface SpeechEngine {
    /** 可以用的时候为 null，否则是给用户看的原因。 */
    val problem: String?
    fun recognize(pcm: ShortArray, from: Int, to: Int): Recognition
}

/** 自带密钥：用户自己的 Google Cloud 服务账号直连 Speech-to-Text v2 Chirp 3。中英双语、说话人分段、词级时间戳。 */
class ChirpClient(private val auth: GoogleAuth) : SpeechEngine {
    override val problem get() = if (auth.available) null else "还没有导入 Google Cloud 服务账号"

    override fun recognize(pcm: ShortArray, from: Int, to: Int): Recognition {
        val endpoint = "https://us-speech.googleapis.com/v2/projects/${auth.projectId}/locations/us/recognizers/_:recognize"
        val body = chirpRequest(pcm, from, to)
        val response = postJson(endpoint, body.toString(), mapOf("Authorization" to "Bearer ${auth.token()}"), readTimeoutMs = 120_000)
        return parseRecognition(Json.parseToJsonElement(response).jsonObject)
    }
}

fun pcmBase64(pcm: ShortArray, from: Int, to: Int): String {
    val bytes = ByteBuffer.allocate((to - from) * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in from until to) bytes.putShort(pcm[i])
    return Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
}

private fun chirpRequest(pcm: ShortArray, from: Int, to: Int) = buildJsonObject {
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
    put("content", pcmBase64(pcm, from, to))
}

/** 解析 Speech-to-Text v2 的识别结果。拾日云原样转发同样的结构。 */
fun parseRecognition(response: JsonObject): Recognition {
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
