import EventKit
import Foundation
import UserNotifications

private let MIN_CONFIDENCE = 0.6
/** 只为最近的对话主动提醒；补转写的旧片段不打扰。 */
private let FRESH_MS: Int64 = 6 * 60 * 60_000

/**
 * 主动的一面：每段对话转写完，就看看有没有该替用户记下的时间和约定，
 * 有就发一条通知，可以提前 15 分钟提醒或加到日程。建议也会出现在首页。
 */
@MainActor
final class Agent: NSObject, UNUserNotificationCenterDelegate {
    static let category = "dayweave.suggestion"
    private let archive: Archive
    private let cloud: CloudAPI
    private let events = EKEventStore()

    init(archive: Archive, cloud: CloudAPI) {
        self.archive = archive
        self.cloud = cloud
        super.init()
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories([UNNotificationCategory(
            identifier: Self.category,
            actions: [
                UNNotificationAction(identifier: "remind", title: tr("提前 15 分钟提醒", "Remind 15 min before")),
                UNNotificationAction(identifier: "calendar", title: tr("加到日程", "Add to calendar"), options: [.foreground]),
            ],
            intentIdentifiers: [],
        )])
    }

    func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func consider(_ segment: Segment) {
        guard Date().ms - segment.endedAt < FRESH_MS, !segment.turns.isEmpty, let code = cloud.licenseCode else { return }
        let input = prompt(segment)
        Task {
            guard let text = try? await CloudAPI.assist("agent", input: input, code: code),
                  let data = text.data(using: .utf8),
                  let proposals = try? JSONDecoder().decode(Proposals.self, from: data) else { return }
            let now = Date().ms
            // 模型偶尔会在某个字段里无限重复，超长的整条丢掉
            let sane = proposals.actions.filter { p in [p.title, p.why ?? "", p.quote ?? ""].allSatisfy { $0.count <= 300 } }
            let fresh: [Suggestion] = sane.filter { $0.confidence >= MIN_CONFIDENCE }.compactMap { p in
                let at = p.time.flatMap(parseTime)
                if p.kind != "todo", at == nil || at! < now { return nil }
                return Suggestion(
                    id: "sug-\(now)-\(abs(p.title.hashValue) % 100_000)", segmentId: segment.id, kind: p.kind, title: p.title,
                    at: at, endAt: p.endTime.flatMap(parseTime), location: p.location, why: p.why ?? "", quote: p.quote ?? "", createdAt: now,
                )
            }
            guard !fresh.isEmpty else { return }
            archive.add(fresh)
            fresh.forEach(notify)
        }
    }

    private func prompt(_ segment: Segment) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm EEEE"
        var out = "当前时间：\(f.string(from: Date()))\n对话时间：\(f.string(from: segment.start))\n"
        if let scene = segment.scene { out += "场景：用户当时在\(scene.kind.label)" }
        out += "\n\n"
        for t in segment.turns { out += "\(speakerName(t.speakerId))：\(t.text)\n" }
        return out
    }

    private func parseTime(_ text: String) -> Int64? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f.date(from: String(text.trimmingCharacters(in: .whitespaces).prefix(16)))?.ms
    }

    private func notify(_ s: Suggestion) {
        let content = UNMutableNotificationContent()
        content.title = s.title
        content.body = [s.at.map { timeLabel($0) }, s.why.isEmpty ? nil : s.why].compactMap { $0 }.joined(separator: " · ")
        content.categoryIdentifier = s.at == nil ? "" : Self.category
        content.userInfo = ["id": s.id]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: s.id, content: content, trigger: nil))
    }

    // MARK: 动作

    /** 提前 15 分钟发本地通知。iOS 不允许第三方 App 设系统闹钟，用定时通知代替。 */
    func remind(_ s: Suggestion) {
        guard let at = s.at else { return }
        let fire = Date(ms: at).addingTimeInterval(-15 * 60)
        guard fire > Date() else { return }
        let content = UNMutableNotificationContent()
        content.title = s.title
        content.body = tr("15 分钟后：", "In 15 minutes: ") + timeLabel(at)
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: fire)
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "remind-\(s.id)", content: content, trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)))
        archive.setStatus(s.id, "done")
    }

    /** 加到日历：只申请“仅添加”权限，读不到用户的其他日程。 */
    func addToCalendar(_ s: Suggestion) async -> Bool {
        guard let at = s.at, (try? await events.requestWriteOnlyAccessToEvents()) == true else { return false }
        let event = EKEvent(eventStore: events)
        event.title = s.title
        event.startDate = Date(ms: at)
        event.endDate = Date(ms: s.endAt ?? at + 60 * 60_000)
        event.location = s.location
        event.notes = [s.why, s.quote].filter { !$0.isEmpty }.joined(separator: "\n")
        event.calendar = events.defaultCalendarForNewEvents
        event.addAlarm(EKAlarm(relativeOffset: -15 * 60))
        do {
            try events.save(event, span: .thisEvent)
            archive.setStatus(s.id, "done")
            return true
        } catch {
            return false
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let id = response.notification.request.content.userInfo["id"] as? String else { return }
        let action = response.actionIdentifier
        await MainActor.run {
            guard let s = archive.suggestions.first(where: { $0.id == id }) else { return }
            if action == "remind" { remind(s) }
            if action == "calendar" { Task { _ = await addToCalendar(s) } }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }
}

private struct Proposed: Decodable {
    let kind: String
    let title: String
    let time: String?
    let endTime: String?
    let location: String?
    let why: String?
    let quote: String?
    let confidence: Double
}

private struct Proposals: Decodable { let actions: [Proposed] }

func timeLabel(_ ms: Int64) -> String {
    let date = Date(ms: ms)
    let cal = Calendar.current
    let time = date.formatted(date: .omitted, time: .shortened)
    if cal.isDateInToday(date) { return tr("今天 \(time)", "Today \(time)") }
    if cal.isDateInTomorrow(date) { return tr("明天 \(time)", "Tomorrow \(time)") }
    return date.formatted(.dateTime.month().day().weekday(.abbreviated).hour().minute())
}
