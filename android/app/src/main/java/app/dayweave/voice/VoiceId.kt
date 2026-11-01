package app.dayweave.voice

import android.content.Context
import app.dayweave.core.OWNER_ID
import app.dayweave.core.Person
import app.dayweave.core.SAMPLE_RATE
import app.dayweave.core.UNKNOWN_ID
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.sqrt
import app.dayweave.core.tr

/** 与主人声纹的余弦相似度达到该值即认为是主人。CAM++ 同一说话人通常在 0.6 以上，不同人多在 0.3 以下。 */
const val OWNER_THRESHOLD = 0.5f
/** 非主人之间的归并阈值：高于它视为同一个人。 */
private const val PERSON_THRESHOLD = 0.55f
/** 太短的发言无法稳定提取声纹，循环拼接到这个长度。 */
private const val MIN_EMBED_SAMPLES = SAMPLE_RATE * 3 / 2
/**
 * 至少这么长的真实人声才允许新建一个说话人。短句（“嗯”“好的”）的声纹噪声很大，
 * 以前每句都可能被当成新的人，一天就能冒出几百个声纹。
 */
private const val MIN_NEW_PERSON_SAMPLES = SAMPLE_RATE * 5 / 2
/** 至少这么长才用来更新已知声纹的均值，避免短句把均值带偏。 */
private const val MIN_LEARN_SAMPLES = SAMPLE_RATE * 3
/** 短句声纹偏离较大，略放宽归属阈值；仍不像任何已知的人就记为未识别。 */
private const val SHORT_MATCH_THRESHOLD = 0.5f
/** 声纹整理：平均相似度高于它的两组声纹建议合并。 */
const val TIDY_LOOSE = 0.42f
const val TIDY_STRICT = 0.52f
/** 只出现过一两次、总共说话不到这么久的声纹算“零散声纹”。 */
private const val FRAGMENT_MS = 8_000L

@Serializable
private data class OwnerVoice(
    val embedding: List<Float>,
    val clips: Int,
    val enrolledAt: Long,
    /** 用户手动标成“我”的声纹，例如换了耳机、感冒时的声音。 */
    val extra: List<List<Float>> = emptyList(),
)

/**
 * 一个被认出的人。名字由用户标注；没标注时叫“说话人 n”。
 * centroid 是自动归并的声纹均值；extra 是用户手动合并进来的其他声纹，同样参与匹配。
 */
@Serializable
private data class KnownPerson(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val centroid: List<Float>,
    val count: Int,
    val named: Boolean = false,
    val extra: List<List<Float>> = emptyList(),
    val ignored: Boolean = false,
)

/**
 * 手机端声纹：CAM++（中英通用）模型在本机提取声纹，声纹不离开手机。
 * 主人通过朗读录入；其他人只给匿名标记（说话人 1、2…），跨片段按声纹归并。
 */
class VoiceId(private val context: Context, root: File) {
    private val dir = File(root, "voice").apply { mkdirs() }
    private val ownerFile = File(dir, "owner.json")
    private val peopleFile = File(dir, "people.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val distinctFile = File(dir, "distinct.json")
    /** 用户确认“不是同一个人”的声纹对，整理时不再建议合并。 */
    private val distinct = runCatching { json.decodeFromString<Set<String>>(distinctFile.readText()) }.getOrDefault(emptySet()).toMutableSet()

    private val extractor by lazy {
        SpeakerEmbeddingExtractor(
            context.assets,
            SpeakerEmbeddingExtractorConfig(model = "speaker/campplus.onnx", numThreads = 2, debug = false, provider = "cpu"),
        )
    }

    private var ownerVoice: OwnerVoice? = readOwnerVoice()
    private var owner: FloatArray? = ownerVoice?.embedding?.toFloatArray()
    private val _ownerEnrolled = MutableStateFlow(owner != null)
    val ownerEnrolled: StateFlow<Boolean> = _ownerEnrolled

    private val known = readPeople().toMutableList()
    private val _people = MutableStateFlow(snapshot())
    /** 所有识别出的人，主人固定存在。 */
    val people: StateFlow<Map<String, Person>> = _people

    private fun readOwnerVoice(): OwnerVoice? = runCatching {
        json.decodeFromString<OwnerVoice>(ownerFile.readText())
    }.getOrNull()

    private fun readPeople(): List<KnownPerson> = runCatching {
        json.decodeFromString<List<KnownPerson>>(peopleFile.readText())
    }.getOrDefault(emptyList())

    private fun snapshot(): Map<String, Person> =
        mapOf(OWNER_ID to Person(OWNER_ID, tr("我", "Me"), 0, isOwner = true)) +
            known.associate { p ->
                // 没起名字的人只存编号，名字按当前语言显示（说话人 3 / Speaker 3）
                val name = if (p.named) p.name else p.id.removePrefix("p").let { n -> tr("说话人 $n", "Speaker $n") }
                p.id to Person(p.id, name, p.colorIndex, named = p.named, ignored = p.ignored)
            }

    /** 切换语言后重新生成显示名。 */
    fun relabel() {
        _people.value = snapshot()
    }

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
        val voice = OwnerVoice(result.toList(), embeddings.size, System.currentTimeMillis(), ownerVoice?.extra.orEmpty())
        ownerFile.writeText(json.encodeToString(OwnerVoice.serializer(), voice))
        ownerVoice = voice
        owner = result
        _ownerEnrolled.value = true
        return true
    }

    @Synchronized
    fun clearOwner() {
        ownerFile.delete()
        ownerVoice = null
        owner = null
        _ownerEnrolled.value = false
    }

    fun ownerScore(embedding: FloatArray): Float? = owner?.let { o ->
        (listOf(o) + ownerVoice?.extra.orEmpty().map { it.toFloatArray() }).maxOf { cosine(it, embedding) }
    }

    private fun KnownPerson.score(embedding: FloatArray) =
        (listOf(centroid) + extra).maxOf { cosine(it.toFloatArray(), embedding) }

    /**
     * 判断一段声纹是谁：先比主人，再比已知的人（含手动合并进来的声纹）。
     * samples 是提取声纹用的真实人声长度：太短的只归给足够像的已知的人，否则记为未识别，不新建说话人。
     */
    @Synchronized
    fun resolve(embedding: FloatArray, samples: Int = MIN_LEARN_SAMPLES): Pair<String, Float?> {
        val score = ownerScore(embedding)
        if (score != null && score >= OWNER_THRESHOLD) return OWNER_ID to score

        val best = known.withIndex().maxByOrNull { it.value.score(embedding) }
        val bestScore = best?.value?.score(embedding) ?: -1f
        val threshold = if (samples >= MIN_NEW_PERSON_SAMPLES) PERSON_THRESHOLD else SHORT_MATCH_THRESHOLD
        if (best != null && bestScore >= threshold) {
            val p = best.value
            if (samples >= MIN_LEARN_SAMPLES) {
                val merged = FloatArray(embedding.size) { (p.centroid[it] * p.count + embedding[it]) / (p.count + 1) }
                known[best.index] = p.copy(centroid = normalize(merged).toList(), count = p.count + 1)
            } else {
                known[best.index] = p.copy(count = p.count + 1)
            }
            persist()
            return p.id to score
        }
        if (samples < MIN_NEW_PERSON_SAMPLES) return UNKNOWN_ID to score

        val n = (known.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1
        known += KnownPerson("p$n", "说话人 $n", n, embedding.toList(), 1)
        persist()
        return "p$n" to score
    }

    private fun voicesOf(id: String): List<FloatArray>? = when (id) {
        OWNER_ID -> owner?.let { o -> listOf(o) + ownerVoice?.extra.orEmpty().map { it.toFloatArray() } }
        else -> known.firstOrNull { it.id == id }?.let { p -> (listOf(p.centroid) + p.extra).map { it.toFloatArray() } }
    }

    /** 两个人的声纹有多像（各自所有声纹两两比较取最大），用于合并时排序。 */
    @Synchronized
    fun similarity(a: String, b: String): Float {
        val va = voicesOf(a) ?: return -1f
        val vb = voicesOf(b) ?: return -1f
        return va.maxOf { x -> vb.maxOf { y -> cosine(x, y) } }
    }

    /**
     * 声纹整理：把很像的声纹分成组，建议合并。平均链接层次聚类，主人和已命名的人作为锚点，
     * 一组里最多一个锚点（不会把两个已命名的人并在一起）；用户说过“不是同一个人”的不再并到一组。
     * talkMs 是每个人在档案里一共说了多久，用来挑每组保留谁、判断零散声纹。
     */
    @Synchronized
    fun tidy(talkMs: Map<String, Long>, threshold: Float): VoiceTidy {
        val ids = buildList { if (owner != null) add(OWNER_ID); addAll(known.filter { !it.ignored }.map { it.id }) }
        val voices = ids.map { voicesOf(it)!! }
        val anchor = BooleanArray(ids.size) { ids[it] == OWNER_ID || known.firstOrNull { p -> p.id == ids[it] }?.named == true }
        val n = ids.size
        // 平均链接：sim[i][j] 是两组之间所有成员两两相似度（取各自声纹最大值）的平均。
        val sim = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val v = voices[i].maxOf { x -> voices[j].maxOf { y -> cosine(x, y) } }
            sim[i][j] = v; sim[j][i] = v
        }
        val size = IntArray(n) { 1 }
        val members = Array(n) { mutableListOf(it) }
        val alive = BooleanArray(n) { true }
        val hasAnchor = anchor.copyOf()
        fun blocked(a: Int, b: Int) = (hasAnchor[a] && hasAnchor[b]) ||
            distinct.isNotEmpty() && members[a].any { x -> members[b].any { y -> pairKey(ids[x], ids[y]) in distinct } }
        while (true) {
            var bi = -1; var bj = -1; var best = threshold
            for (i in 0 until n) if (alive[i]) for (j in i + 1 until n) if (alive[j] && sim[i][j] >= best && !blocked(i, j)) {
                best = sim[i][j]; bi = i; bj = j
            }
            if (bi < 0) break
            for (k in 0 until n) if (alive[k] && k != bi && k != bj) {
                val v = (sim[bi][k] * size[bi] + sim[bj][k] * size[bj]) / (size[bi] + size[bj])
                sim[bi][k] = v; sim[k][bi] = v
            }
            size[bi] += size[bj]; members[bi] += members[bj]; hasAnchor[bi] = hasAnchor[bi] || hasAnchor[bj]
            alive[bj] = false
        }
        val groups = (0 until n).filter { alive[it] && members[it].size > 1 }.map { g ->
            val list = members[g].map { ids[it] }
            val keep = list.firstOrNull { it == OWNER_ID } ?: list.firstOrNull { id -> known.any { it.id == id && it.named } }
                ?: list.maxBy { talkMs[it] ?: 0L }
            VoiceGroup(keep, listOf(keep) + (list - keep).sortedByDescending { talkMs[it] ?: 0L })
        }.sortedByDescending { g -> g.members.sumOf { talkMs[it] ?: 0L } }
        val grouped = groups.flatMap { it.members }.toSet()
        val fragments = known.filter { p -> !p.named && !p.ignored && p.id !in grouped && p.count <= 2 && (talkMs[p.id] ?: 0L) < FRAGMENT_MS }.map { it.id }
        return VoiceTidy(groups, fragments, known.size)
    }

    /** 设为路人或取消。路人的声纹保留，之后再听到仍归给他，只是不在总结和整理里出现。 */
    @Synchronized
    fun setIgnored(id: String, ignored: Boolean) = setIgnored(listOf(id), ignored)

    @Synchronized
    fun setIgnored(ids: Collection<String>, ignored: Boolean) {
        val set = ids.toSet()
        if (known.none { it.id in set }) return
        known.replaceAll { if (it.id in set) it.copy(ignored = ignored) else it }
        persist()
    }

    /** 用户说这两个声纹不是同一个人。 */
    @Synchronized
    fun markDistinct(a: String, b: String) {
        distinct += pairKey(a, b)
        distinctFile.writeText(json.encodeToString(kotlinx.serialization.builtins.SetSerializer(String.serializer()), distinct.toSet()))
    }

    /** 忘掉这些声纹（零散声纹）。调用方负责把档案里他们的发言改成未识别。 */
    @Synchronized
    fun forget(ids: Collection<String>) {
        val set = ids.toSet() - OWNER_ID
        if (known.removeAll { it.id in set }) persist()
    }

    /** 给一个人起名字。之后识别到同样的声纹会自动显示这个名字。 */
    @Synchronized
    fun rename(id: String, name: String) {
        val i = known.indexOfFirst { it.id == id }
        if (i < 0 || name.isBlank()) return
        known[i] = known[i].copy(name = name.trim(), named = true)
        persist()
    }

    /**
     * 把 from 合并进 into（“这两个是同一个人”）：from 的声纹成为 into 的额外声纹，之后两种声音都认作 into。
     * into 为主人时，声纹加入主人的额外声纹。调用方负责把档案里的说话人一并改掉。
     */
    @Synchronized
    fun merge(from: String, into: String) {
        if (from == into || from == OWNER_ID) return
        val source = known.firstOrNull { it.id == from } ?: return
        val voices = listOf(source.centroid) + source.extra
        if (into == OWNER_ID) {
            val o = ownerVoice ?: return
            val updated = o.copy(extra = o.extra + voices)
            ownerFile.writeText(json.encodeToString(OwnerVoice.serializer(), updated))
            ownerVoice = updated
        } else {
            val i = known.indexOfFirst { it.id == into }
            if (i < 0) return
            known[i] = known[i].copy(extra = known[i].extra + voices, count = known[i].count + source.count)
        }
        known.removeAll { it.id == from }
        persist()
    }

    private fun persist() {
        peopleFile.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(KnownPerson.serializer()), known))
        _people.value = snapshot()
    }

    @Synchronized
    fun forgetPeople() {
        known.clear()
        peopleFile.delete()
        distinct.clear()
        distinctFile.delete()
        _people.value = snapshot()
    }
}

/** 一组很像的声纹：members 第一个是保留的人（主人、已命名的人或说话最多的人）。 */
data class VoiceGroup(val keep: String, val members: List<String>)

/** 声纹整理的结果：建议合并的组、零散声纹，以及当前声纹总数。 */
data class VoiceTidy(val groups: List<VoiceGroup>, val fragments: List<String>, val total: Int)

private fun pairKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

private fun normalize(v: FloatArray): FloatArray {
    val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
    return if (norm == 0f) v else FloatArray(v.size) { v[it] / norm }
}

private fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}
