import Foundation
import Observation

/**
 * 文字档案：片段、每日整理和主动提醒，都以 JSON 存在 App 的 Application Support 里，不上传。
 * 临时音频放在 Caches/segments，转写完即删除。
 */
@MainActor
@Observable
final class Archive {
    private(set) var segments: [Segment] = []
    private(set) var digests: [String: DayDigest] = [:]
    private(set) var suggestions: [Suggestion] = []

    let root: URL
    let audioDir: URL
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }()

    init() {
        let fm = FileManager.default
        root = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Dayweave", isDirectory: true)
        audioDir = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("segments", isDirectory: true)
        try? fm.createDirectory(at: root, withIntermediateDirectories: true)
        try? fm.createDirectory(at: audioDir, withIntermediateDirectories: true)
        segments = load("segments.json") ?? []
        digests = load("digests.json") ?? [:]
        suggestions = load("suggestions.json") ?? []
        // 上次退出时还在转写的片段，重新排队
        for i in segments.indices where segments[i].status == .TRANSCRIBING { segments[i].status = .PENDING }
    }

    private func load<T: Decodable>(_ name: String) -> T? {
        guard let data = try? Data(contentsOf: root.appendingPathComponent(name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func save<T: Encodable>(_ value: T, _ name: String) {
        guard let data = try? encoder.encode(value) else { return }
        // 档案只在本机，开启文件保护：锁屏后不可读
        try? data.write(to: root.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func audioURL(_ id: String) -> URL { audioDir.appendingPathComponent("\(id).wav") }

    // MARK: 片段

    func upsert(_ segment: Segment) {
        if let i = segments.firstIndex(where: { $0.id == segment.id }) { segments[i] = segment } else { segments.append(segment) }
        segments.sort { $0.startedAt > $1.startedAt }
        save(segments, "segments.json")
    }

    func segment(_ id: String) -> Segment? { segments.first { $0.id == id } }

    func delete(_ id: String) {
        segments.removeAll { $0.id == id }
        try? FileManager.default.removeItem(at: audioURL(id))
        save(segments, "segments.json")
    }

    func segments(on day: String) -> [Segment] {
        segments.filter { dayKey($0.start) == day && $0.status == .DONE && !$0.turns.isEmpty }.sorted { $0.startedAt < $1.startedAt }
    }

    /** 有对话的日子，从近到远。 */
    var days: [String] {
        var seen = Set<String>()
        return segments.filter { $0.status == .DONE && !$0.turns.isEmpty }.map { dayKey($0.start) }.filter { seen.insert($0).inserted }
    }

    // MARK: 整理与提醒

    func setDigest(_ digest: DayDigest) {
        digests[digest.date] = digest
        save(digests, "digests.json")
    }

    func add(_ new: [Suggestion]) {
        suggestions.insert(contentsOf: new, at: 0)
        save(suggestions, "suggestions.json")
    }

    func setStatus(_ id: String, _ status: String) {
        guard let i = suggestions.firstIndex(where: { $0.id == id }) else { return }
        suggestions[i].status = status
        save(suggestions, "suggestions.json")
    }

    var openSuggestions: [Suggestion] {
        let now = Date().ms
        return suggestions.filter { $0.status == "new" && ($0.at ?? .max) > now }
    }

    func wipe() {
        segments = []; digests = [:]; suggestions = []
        for name in ["segments.json", "digests.json", "suggestions.json"] { try? FileManager.default.removeItem(at: root.appendingPathComponent(name)) }
        try? FileManager.default.removeItem(at: audioDir)
        try? FileManager.default.createDirectory(at: audioDir, withIntermediateDirectories: true)
    }
}
