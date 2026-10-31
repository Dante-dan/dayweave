#if DEBUG
import Foundation

/** 上架截图用的示例档案：-demo 启动时写入（只在调试版存在）。内容是虚构的日常，不含真实对话。 */
@MainActor
enum Demo {
    static func seed(_ archive: Archive) {
        archive.wipe()
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        func at(_ dayOffset: Int, _ h: Int, _ m: Int) -> Date { cal.date(byAdding: .minute, value: h * 60 + m, to: cal.date(byAdding: .day, value: dayOffset, to: today)!)! }
        let zh = !Lang.english

        func seg(_ id: String, _ start: Date, minutes: Int, _ lines: [(Int, String)]) -> Segment {
            var t: Int64 = 0
            let turns = lines.map { (who, text) -> Turn in
                defer { t += 9_000 }
                return Turn(speakerId: "speaker-\(who)", startMs: t, endMs: t + 8_000, text: text)
            }
            return Segment(id: id, startedAt: start.ms, endedAt: start.addingTimeInterval(Double(minutes * 60)).ms, device: tr("iPhone 麦克风", "iPhone mic"),
                           status: .DONE, turns: turns, speechMs: Int64(minutes * 45_000))
        }

        let segments: [Segment] = zh ? [
            seg("demo-1", at(0, 9, 12), minutes: 6, [(0, "早，今天的读书会改到下午了吗？"), (1, "对，三点，湖边那家书店，二楼靠窗。"), (0, "好呀，三点书店见，我把那本诗集带上。")]),
            seg("demo-2", at(0, 8, 30), minutes: 4, [(0, "我想周末学一点胶片摄影，先从那台老相机开始。"), (1, "可以先拍一卷黑白的，冲洗便宜，也好看。")]),
            seg("demo-3", at(-1, 19, 40), minutes: 12, [(1, "海边那趟我们周五出发吧，路上不堵。"), (0, "行，周五早上八点楼下集合，我来订民宿。"), (2, "记得带那台旧的胶片相机。")]),
            seg("demo-4", at(-1, 14, 5), minutes: 9, [(0, "这个季度的目标我想再收一收，先把一件事做好。"), (1, "同意，先把录音转写的体验打磨到位。")]),
        ] : [
            seg("demo-1", at(0, 9, 12), minutes: 6, [(0, "Morning! Did the book club move to this afternoon?"), (1, "Yes, three o'clock at the bookshop by the lake, upstairs by the window."), (0, "Lovely, see you at three. I'll bring the poetry book.")]),
            seg("demo-2", at(0, 8, 30), minutes: 4, [(0, "I'd like to try film photography this weekend, starting with the old camera."), (1, "Shoot a roll of black and white first. It's cheap to develop and looks beautiful.")]),
            seg("demo-3", at(-1, 19, 40), minutes: 12, [(1, "Let's leave for the coast on Friday, the roads are quiet."), (0, "Sounds good. Friday at eight downstairs, and I'll book the cottage."), (2, "Don't forget the old film camera.")]),
            seg("demo-4", at(-1, 14, 5), minutes: 9, [(0, "I want to narrow this quarter's goals and do one thing really well."), (1, "Agreed. Let's polish the transcription experience first.")]),
        ]
        segments.forEach(archive.upsert)

        let d0 = dayKey(at(0, 12, 0)), d1 = dayKey(at(-1, 12, 0))
        archive.setDigest(DayDigest(
            date: d0, headline: tr("诗集、胶片与湖边的约", "Poems, film and a lakeside plan"),
            summary: tr("上午定下了下午三点在湖边书店的读书会，你答应带上那本诗集。早些时候聊到想学胶片摄影，朋友建议先拍一卷黑白。",
                        "This morning the book club moved to three o'clock at the lakeside bookshop, and you offered to bring the poetry book. Earlier you talked about trying film photography; a friend suggested starting with black and white."),
            followUps: [FollowUp(text: tr("下午 3 点湖边书店，带上诗集", "3 pm at the lakeside bookshop — bring the poetry book"), owner: "me")],
            insights: [Insight(text: tr("这周你两次提到想慢下来做点手上的事，胶片摄影也许是个好开始。", "Twice this week you mentioned wanting to slow down with something hands-on; film photography could be a good start."), kind: "suggestion")],
            topics: zh ? ["读书会", "胶片摄影"] : ["book club", "film photography"],
            sourceIds: ["demo-1", "demo-2"], generatedAt: Date().ms))
        archive.setDigest(DayDigest(
            date: d1, headline: tr("周五去海边，目标收一收", "Coast on Friday, fewer goals"),
            summary: tr("决定周五早上八点出发去海边，你负责订民宿；下午和同事商量把季度目标收窄，先打磨好一件事。",
                        "You decided to leave for the coast on Friday at eight and offered to book the cottage. In the afternoon you agreed with a colleague to narrow the quarter's goals and polish one thing first."),
            decisions: [Cited(text: tr("周五 8:00 楼下集合出发", "Leave Friday at 8:00 from downstairs"))],
            followUps: [FollowUp(text: tr("订海边的民宿", "Book the cottage by the sea"), owner: "me")],
            topics: zh ? ["海边", "季度目标"] : ["coast trip", "goals"],
            sourceIds: ["demo-3", "demo-4"], generatedAt: Date().ms))

        let three = cal.date(bySettingHour: 15, minute: 0, second: 0, of: Date())!
        let friday = cal.nextDate(after: Date(), matching: DateComponents(hour: 8, minute: 0, weekday: 6), matchingPolicy: .nextTime)!
        archive.add([
            Suggestion(id: "demo-s1", segmentId: "demo-1", kind: "event", title: tr("湖边书店读书会", "Book club at the lakeside bookshop"), at: three.ms,
                       why: tr("朋友约你下午三点在书店二楼见，你答应带上诗集。", "A friend asked to meet upstairs at three; you'll bring the poetry book."),
                       quote: tr("好呀，三点书店见，我把那本诗集带上。", "Lovely, see you at three. I'll bring the poetry book."), createdAt: Date().ms),
            Suggestion(id: "demo-s2", segmentId: "demo-3", kind: "event", title: tr("出发去海边", "Leave for the coast"), at: friday.ms,
                       why: tr("约好周五早上八点楼下集合。", "You agreed to meet downstairs on Friday at eight."),
                       quote: tr("周五早上八点楼下集合", "Friday at eight downstairs"), createdAt: Date().ms),
        ])
    }
}
#endif
