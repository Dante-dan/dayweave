package app.dayweave.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 文字档案：files/archive/<yyyy-MM-dd>/<segmentId>.json，每个片段一个文件，便于迁移、检索和交给 Agent。
 * 听音乐、播客等场景单独存在 files/listening/<yyyy-MM-dd>/<id>.json，没有音频。
 * 写入先落到临时文件再原子替换，避免崩溃时留下半截 JSON。
 */
class ArchiveStore(root: File) {
    private val segmentFiles = DatedFiles(File(root, "archive"), Segment.serializer(), { it.id }, { it.startedAt })
    private val listeningFiles = DatedFiles(File(root, "listening"), Listening.serializer(), { it.id }, { it.startedAt })

    private val _segments = MutableStateFlow(segmentFiles.load())
    /** 全部片段，按开始时间倒序。 */
    val segments: StateFlow<List<Segment>> = _segments

    private val _listening = MutableStateFlow(listeningFiles.load())
    /** 听音乐、播客、视频与通话的时间段，按开始时间倒序。 */
    val listening: StateFlow<List<Listening>> = _listening

    @Synchronized
    fun save(segment: Segment) {
        segmentFiles.write(segment)
        _segments.update { list -> (list.filterNot { it.id == segment.id } + segment).sortedByDescending { it.startedAt } }
    }

    @Synchronized
    fun save(listening: Listening) {
        listeningFiles.write(listening)
        _listening.update { list -> (list.filterNot { it.id == listening.id } + listening).sortedByDescending { it.startedAt } }
    }

    fun get(id: String): Segment? = _segments.value.firstOrNull { it.id == id }

    @Synchronized
    fun delete(id: String) {
        val segment = get(id) ?: return
        segmentFiles.delete(segment)
        _segments.update { list -> list.filterNot { it.id == id } }
    }

    /** 把档案里某个说话人的发言全部改成另一个人，用于合并说话人。 */
    @Synchronized
    fun reassignSpeaker(from: String, to: String) {
        _segments.value.filter { s -> s.turns.any { it.speakerId == from } }.forEach { s ->
            save(s.copy(turns = s.turns.map { if (it.speakerId == from) it.copy(speakerId = to) else it }))
        }
    }

    @Synchronized
    fun deleteAll() {
        segmentFiles.clear()
        listeningFiles.clear()
        _segments.value = emptyList()
        _listening.value = emptyList()
    }
}

/** 按天分目录、每条一个 JSON 文件的存储。 */
private class DatedFiles<T>(
    private val dir: File,
    private val serializer: KSerializer<T>,
    private val id: (T) -> String,
    private val time: (T) -> Long,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    init {
        dir.mkdirs()
    }

    fun load(): List<T> = dir.walkTopDown()
        .filter { it.isFile && it.extension == "json" }
        .mapNotNull { runCatching { json.decodeFromString(serializer, it.readText()) }.getOrNull() }
        .sortedByDescending(time)
        .toList()

    private fun fileFor(item: T): File {
        val folder = File(dir, day.format(Instant.ofEpochMilli(time(item)))).apply { mkdirs() }
        return File(folder, "${id(item)}.json")
    }

    fun write(item: T) {
        val target = fileFor(item)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, item))
        check(tmp.renameTo(target)) { "无法写入 ${target.name}" }
    }

    fun delete(item: T) {
        fileFor(item).delete()
    }

    fun clear() {
        dir.deleteRecursively()
        dir.mkdirs()
    }
}
