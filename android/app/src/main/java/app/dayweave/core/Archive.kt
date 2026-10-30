package app.dayweave.core

import kotlinx.serialization.Serializable

/** 片段来自哪条输入。PLAYBACK 只出现在旧版本的档案里：现在不再录制手机播放的声音。 */
@Serializable
enum class SourceKind { MIC, PLAYBACK }

/** 手机当时在放什么。只读系统公开的播放状态，不录制这些声音。 */
@Serializable
enum class SceneKind(val label: String, val doing: String) {
    MUSIC("听音乐", "听音乐时"),
    PODCAST("听播客", "听播客时"),
    VIDEO("看视频", "看视频时"),
    CALL("通话 / 会议", "通话或开会时"),
    MEDIA("播放声音", "手机在播放声音时"),
}

/** 片段发生时的场景：哪类声音、哪个 App、正在放什么（开启“识别正在播放的内容”后才有 App 与标题）。 */
@Serializable
data class Scene(val kind: SceneKind, val app: String? = null, val title: String? = null) {
    val label get() = listOfNotNull(kind.label, app).joinToString(" · ")
}

/** 一段听音乐、听播客、看视频或通话的时间。没有音频，只记录时间和来源，作为时间线和整理的背景。 */
@Serializable
data class Listening(
    val id: String,
    val scene: Scene,
    val startedAt: Long,
    val endedAt: Long,
) {
    val durationMs get() = endedAt - startedAt
}

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
    /** 本地人声检测后实际送去转写的时长，也是计费时长。静音、环境音不计入。 */
    val speechMs: Long = 0,
    /** 片段开始时手机正在播放的内容，例如听音乐时有人找你。 */
    val scene: Scene? = null,
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
    /** 用户标注过名字。 */
    val named: Boolean = false,
) {
    /** 头像上的字：主人是“我”，标注过的人取名字的第一个字，匿名说话人取编号。 */
    val initial: String
        get() = when {
            isOwner -> "我"
            named -> name.trim().take(1).uppercase()
            else -> name.filter { it.isDigit() }.ifEmpty { name.take(1) }
        }
}

const val OWNER_ID = "owner"
const val UNKNOWN_ID = "unknown"
