package app.dayweave.core

import kotlinx.serialization.Serializable

/** 片段来自哪条输入：麦克风（耳机或手机）或手机正在播放的声音。 */
@Serializable
enum class SourceKind { MIC, PLAYBACK }

@Serializable
enum class SegmentStatus { PENDING, TRANSCRIBING, DONE, FAILED }

/**
 * 一段连续的对话。音频只是临时材料：文字可靠保存后，对应的 WAV 会被删除。
 * 时间均为 epoch 毫秒。
 */
@Serializable
data class Segment(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val source: SourceKind,
    val device: String,
    val status: SegmentStatus = SegmentStatus.PENDING,
    val turns: List<Turn> = emptyList(),
    val error: String? = null,
    val languages: List<String> = emptyList(),
) {
    val durationMs get() = endedAt - startedAt
    val speakerIds get() = turns.map { it.speakerId }.distinct()
    val preview get() = turns.joinToString(" ") { it.text }.trim()
}

/** 同一个人连续说的一段话。offset 相对片段开始，单位毫秒。 */
@Serializable
data class Turn(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** 与主人声纹的余弦相似度，便于调试阈值；无法计算时为 null。 */
    val ownerScore: Float? = null,
)

/** 识别出的人。主人通过声纹录入确定，其他人只用匿名标记。 */
@Serializable
data class Person(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val isOwner: Boolean = false,
)

const val OWNER_ID = "owner"
const val UNKNOWN_ID = "unknown"
