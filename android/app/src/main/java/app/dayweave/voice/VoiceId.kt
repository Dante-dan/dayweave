package app.dayweave.voice

import android.content.Context
import app.dayweave.core.OWNER_ID
import app.dayweave.core.Person
import app.dayweave.core.SAMPLE_RATE
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.sqrt

/** 与主人声纹的余弦相似度达到该值即认为是主人。CAM++ 同一说话人通常在 0.6 以上，不同人多在 0.3 以下。 */
const val OWNER_THRESHOLD = 0.5f
/** 非主人之间的归并阈值：高于它视为同一个人。 */
private const val PERSON_THRESHOLD = 0.55f
/** 太短的发言无法稳定提取声纹，循环拼接到这个长度。 */
private const val MIN_EMBED_SAMPLES = SAMPLE_RATE * 3 / 2

@Serializable
private data class OwnerVoice(val embedding: List<Float>, val clips: Int, val enrolledAt: Long)

@Serializable
private data class KnownPerson(val id: String, val name: String, val colorIndex: Int, val centroid: List<Float>, val count: Int)

/**
 * 手机端声纹：CAM++（中英通用）模型在本机提取声纹，声纹不离开手机。
 * 主人通过朗读录入；其他人只给匿名标记（说话人 1、2…），跨片段按声纹归并。
 */
class VoiceId(private val context: Context, root: File) {
    private val dir = File(root, "voice").apply { mkdirs() }
    private val ownerFile = File(dir, "owner.json")
    private val peopleFile = File(dir, "people.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val extractor by lazy {
        SpeakerEmbeddingExtractor(
            context.assets,
            SpeakerEmbeddingExtractorConfig(model = "speaker/campplus.onnx", numThreads = 2, debug = false, provider = "cpu"),
        )
    }

    private var owner: FloatArray? = readOwner()
    private val _ownerEnrolled = MutableStateFlow(owner != null)
    val ownerEnrolled: StateFlow<Boolean> = _ownerEnrolled

    private val known = readPeople().toMutableList()
    private val _people = MutableStateFlow(snapshot())
    /** 所有识别出的人，主人固定存在。 */
    val people: StateFlow<Map<String, Person>> = _people

    private fun readOwner(): FloatArray? = runCatching {
        json.decodeFromString<OwnerVoice>(ownerFile.readText()).embedding.toFloatArray()
    }.getOrNull()

    private fun readPeople(): List<KnownPerson> = runCatching {
        json.decodeFromString<List<KnownPerson>>(peopleFile.readText())
    }.getOrDefault(emptyList())

    private fun snapshot(): Map<String, Person> =
        mapOf(OWNER_ID to Person(OWNER_ID, "我", 0, isOwner = true)) +
            known.associate { it.id to Person(it.id, it.name, it.colorIndex) }

    /** 提取一段音频的归一化声纹；音频太短时循环拼接。 */
    @Synchronized
    fun embed(pcm: ShortArray): FloatArray? {
        if (pcm.isEmpty()) return null
        val length = maxOf(pcm.size, MIN_EMBED_SAMPLES)
        val samples = FloatArray(length) { pcm[it % pcm.size] / 32768f }
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) normalize(extractor.compute(stream)) else null
        } finally {
            stream.release()
        }
    }

    /** 用多段朗读录音录入主人声纹：取各段声纹的平均。 */
    @Synchronized
    fun enroll(clips: List<ShortArray>): Boolean {
        val embeddings = clips.mapNotNull(::embed)
        if (embeddings.isEmpty()) return false
        val mean = FloatArray(embeddings[0].size)
        embeddings.forEach { e -> e.indices.forEach { mean[it] += e[it] } }
        val result = normalize(mean)
        ownerFile.writeText(json.encodeToString(OwnerVoice.serializer(), OwnerVoice(result.toList(), embeddings.size, System.currentTimeMillis())))
        owner = result
        _ownerEnrolled.value = true
        return true
    }

    @Synchronized
    fun clearOwner() {
        ownerFile.delete()
        owner = null
        _ownerEnrolled.value = false
    }

    fun ownerScore(embedding: FloatArray): Float? = owner?.let { cosine(it, embedding) }

    /** 判断一段声纹是谁：先比主人，再比已知的匿名说话人，都不像就新建一个。 */
    @Synchronized
    fun resolve(embedding: FloatArray): Pair<String, Float?> {
        val score = ownerScore(embedding)
        if (score != null && score >= OWNER_THRESHOLD) return OWNER_ID to score

        val best = known.withIndex().maxByOrNull { cosine(it.value.centroid.toFloatArray(), embedding) }
        if (best != null && cosine(best.value.centroid.toFloatArray(), embedding) >= PERSON_THRESHOLD) {
            val p = best.value
            val merged = FloatArray(embedding.size) { (p.centroid[it] * p.count + embedding[it]) / (p.count + 1) }
            known[best.index] = p.copy(centroid = normalize(merged).toList(), count = p.count + 1)
            persist()
            return p.id to score
        }

        val n = known.size + 1
        known += KnownPerson("p$n", "说话人 $n", n, embedding.toList(), 1)
        persist()
        return "p$n" to score
    }

    private fun persist() {
        peopleFile.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(KnownPerson.serializer()), known))
        _people.value = snapshot()
    }

    @Synchronized
    fun forgetPeople() {
        known.clear()
        peopleFile.delete()
        _people.value = snapshot()
    }
}

private fun normalize(v: FloatArray): FloatArray {
    val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
    return if (norm == 0f) v else FloatArray(v.size) { v[it] / norm }
}

private fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}
