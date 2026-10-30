package app.dayweave.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 文字档案：files/archive/<yyyy-MM-dd>/<segmentId>.json，每个片段一个文件，便于迁移、检索和交给 Agent。
 * 写入先落到临时文件再原子替换，避免崩溃时留下半截 JSON。
 */
class ArchiveStore(root: File) {
    private val dir = File(root, "archive").apply { mkdirs() }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    private val _segments = MutableStateFlow(load())
    /** 全部片段，按开始时间倒序。 */
    val segments: StateFlow<List<Segment>> = _segments

    private fun load(): List<Segment> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { runCatching { json.decodeFromString<Segment>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAt }
            .toList()

    private fun fileFor(segment: Segment): File {
        val folder = File(dir, day.format(Instant.ofEpochMilli(segment.startedAt))).apply { mkdirs() }
        return File(folder, "${segment.id}.json")
    }

    @Synchronized
    fun save(segment: Segment) {
        val target = fileFor(segment)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(json.encodeToString(Segment.serializer(), segment))
        check(tmp.renameTo(target)) { "无法写入 ${target.name}" }
        _segments.update { list -> (list.filterNot { it.id == segment.id } + segment).sortedByDescending { it.startedAt } }
    }

    fun get(id: String): Segment? = _segments.value.firstOrNull { it.id == id }

    @Synchronized
    fun delete(id: String) {
        val segment = get(id) ?: return
        fileFor(segment).delete()
        _segments.update { list -> list.filterNot { it.id == id } }
    }

    @Synchronized
    fun deleteAll() {
        dir.deleteRecursively()
        dir.mkdirs()
        _segments.value = emptyList()
    }
}
