import Foundation

// 与安卓版 core/Archive.kt 相同的档案结构，JSON 字段一致，便于以后互通。时间均为 epoch 毫秒。

enum SourceKind: String, Codable { case MIC, PLAYBACK, CALL }

/** 手机当时在放什么。只读系统公开的播放状态，不录制这些声音。 */
enum SceneKind: String, Codable {
    case MUSIC, PODCAST, VIDEO, CALL, MEDIA

    var label: String {
        switch self {
        case .MUSIC: tr("听音乐", "Music")
        case .PODCAST: tr("听播客", "Podcast")
        case .VIDEO: tr("看视频", "Video")
        case .CALL: tr("通话", "Call")
        case .MEDIA: tr("播放声音", "Playing")
        }
    }

    /** 用于句子里：“听音乐时……”。 */
    var doing: String {
        switch self {
        case .MUSIC: tr("听音乐时", "while listening to music")
        case .PODCAST: tr("听播客时", "while listening to a podcast")
        case .VIDEO: tr("看视频时", "while watching a video")
        case .CALL: tr("通话或开会时", "during a call or meeting")
        case .MEDIA: tr("手机在播放声音时", "while the phone was playing audio")
        }
    }
}

struct AudioScene: Codable, Hashable {
    var kind: SceneKind
    var app: String? = nil
    var title: String? = nil
}

enum SegmentStatus: String, Codable { case PENDING, TRANSCRIBING, DONE, FAILED }

/** 同一个人连续说的一段话。offset 相对片段开始，单位毫秒。 */
struct Turn: Codable, Hashable {
    var speakerId: String
    var startMs: Int64
    var endMs: Int64
    var text: String
}

/** 一段连续的对话。音频只是临时材料：文字保存后对应的 WAV 即删除。 */
struct Segment: Codable, Identifiable, Hashable {
    var id: String
    var startedAt: Int64
    var endedAt: Int64
    var source: SourceKind = .MIC
    var device: String
    var status: SegmentStatus = .PENDING
    var turns: [Turn] = []
    var error: String? = nil
    var languages: [String] = []
    /** 实际送去转写的人声时长，也是计费时长。 */
    var speechMs: Int64 = 0
    var scene: AudioScene? = nil

    var durationMs: Int64 { endedAt - startedAt }
    var speakerIds: [String] {
        var seen = Set<String>()
        return turns.map(\.speakerId).filter { seen.insert($0).inserted }
    }
    var preview: String { turns.map(\.text).joined(separator: " ").trimmingCharacters(in: .whitespaces) }
    var start: Date { Date(ms: startedAt) }
}

struct Cited: Codable, Hashable { var text: String; var segmentIds: [String]? = nil }
struct FollowUp: Codable, Hashable { var text: String; var owner: String? = "unclear"; var segmentIds: [String]? = nil }
struct Insight: Codable, Hashable { var text: String; var kind: String? = "inference"; var segmentIds: [String]? = nil }

/** 一天的整理。字段与服务端 digest 的输出结构一致。 */
struct DayDigest: Codable, Hashable {
    var date: String
    var headline: String
    var summary: String
    var highlights: [Cited]? = nil
    var decisions: [Cited]? = nil
    var followUps: [FollowUp]? = nil
    var insights: [Insight]? = nil
    var topics: [String]? = nil
    var sourceIds: [String]? = nil
    var generatedAt: Int64? = nil
}

/** 主动提醒：从对话里发现的约定、闹钟和待办。 */
struct Suggestion: Codable, Identifiable, Hashable {
    var id: String
    var segmentId: String
    var kind: String // alarm | event | todo
    var title: String
    var at: Int64? = nil
    var endAt: Int64? = nil
    var location: String? = nil
    var why: String = ""
    var quote: String = ""
    var createdAt: Int64
    var status: String = "new" // new | done | dismissed
}

extension Date {
    init(ms: Int64) { self.init(timeIntervalSince1970: TimeInterval(ms) / 1000) }
    var ms: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
}

func dayKey(_ date: Date) -> String {
    let f = DateFormatter()
    f.calendar = Calendar(identifier: .gregorian)
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "yyyy-MM-dd"
    return f.string(from: date)
}
