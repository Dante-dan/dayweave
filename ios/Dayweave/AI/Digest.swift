import Foundation
import Observation

/**
 * 每日整理：把某一天的转写按与安卓版相同的格式交给拾日云（提示词在服务端），得到标题、小结、要跟进的事和洞察。
 * 新对话进来后整理会过期；回到首页时按需重新生成，同一天至少间隔 20 分钟，避免频繁调用。
 */
@MainActor
@Observable
final class Digests {
    private(set) var working: Set<String> = []
    private(set) var errors: [String: String] = [:]
    private let archive: Archive
    private let cloud: CloudAPI

    init(archive: Archive, cloud: CloudAPI) {
        self.archive = archive
        self.cloud = cloud
    }

    func isStale(_ day: String) -> Bool {
        let ids = archive.segments(on: day).map(\.id)
        guard !ids.isEmpty else { return false }
        guard let d = archive.digests[day] else { return true }
        return Set(d.sourceIds ?? []) != Set(ids)
    }

    func refreshIfNeeded(_ day: String) {
        guard isStale(day) else { return }
        let last = archive.digests[day]?.generatedAt ?? 0
        if Date().ms - last < 20 * 60_000 { return }
        refresh(day)
    }

    func refresh(_ day: String) {
        guard !working.contains(day), let code = cloud.licenseCode, cloud.problem == nil else { return }
        let sources = archive.segments(on: day)
        guard !sources.isEmpty else { return }
        working.insert(day)
        errors[day] = nil
        let input = transcript(day, sources)
        Task {
            defer { working.remove(day) }
            do {
                let text = try await CloudAPI.assist("digest", input: input, code: code)
                // 模型输出里没有 date 字段，补上再解析
                let patched = text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "{", with: "{\"date\":\"\(day)\",", options: [], range: text.range(of: "{"))
                var digest = try JSONDecoder().decode(DayDigest.self, from: Data(patched.utf8))
                digest.sourceIds = sources.map(\.id)
                digest.generatedAt = Date().ms
                archive.setDigest(digest)
            } catch {
                errors[day] = tr("整理失败，稍后再试", "Couldn't write the digest. Try again later")
            }
        }
    }

    private func transcript(_ day: String, _ sources: [Segment]) -> String {
        let clock = DateFormatter()
        clock.dateFormat = "HH:mm"
        // iOS 暂时只开默认的六位助手（记账、衍生模块在安卓版上按需开启）
        var out = "日期：\(day)\n启用的助手：parenting、health、relationship、finance、life、growth\n\n"
        for s in sources {
            let scene = s.scene.map { "，\($0.kind.doing)" } ?? ""
            out += "## 片段 \(s.id)（\(clock.string(from: s.start))–\(clock.string(from: Date(ms: s.endedAt)))，麦克风·\(s.device)\(scene)）\n"
            for t in s.turns { out += "\(speakerName(t.speakerId))：\(t.text)\n" }
            out += "\n"
        }
        return out
    }
}
