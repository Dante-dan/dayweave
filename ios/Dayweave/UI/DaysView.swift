import SwiftUI

/** 首页：问候、一天的日轨、拾日为你留意的事、按天聚合的整理。 */
struct DaysView: View {
    @Environment(Archive.self) private var archive
    @Environment(Digests.self) private var digests
    @Environment(CloudAPI.self) private var cloud
    @State private var path: [String] = CommandLine.arguments.contains("-detail") ? [dayKey(Date())] : []

    var body: some View {
        NavigationStack(path: $path) { content }
    }

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                DayArc().padding(.horizontal, 4)
                if archive.segments(on: dayKey(Date())).isEmpty {
                    Text(tr("今天还没有拾起什么", "Nothing picked up yet today")).font(.footnote).foregroundStyle(Ink.secondary).padding(.horizontal, 4)
                }
                if !archive.openSuggestions.isEmpty {
                    Label(tr("拾日为你留意", "Dayweave noticed"), systemImage: "sparkle")
                        .font(.system(size: 14, weight: .semibold)).foregroundStyle(Ink.accent).padding(.horizontal, 4)
                    ForEach(archive.openSuggestions.prefix(4)) { SuggestionCard(suggestion: $0) }
                }
                ForEach(archive.days, id: \.self) { day in
                    NavigationLink(value: day) { DayCard(day: day) }.buttonStyle(Pressable(scale: 0.98))
                }
                if archive.days.isEmpty { EmptyHint() }
            }
            .padding(.horizontal, 16).padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background(Aurora())
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(for: String.self) { DayDetail(day: $0) }
        .onAppear { archive.days.prefix(2).forEach(digests.refreshIfNeeded) }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Image("Mark").resizable().frame(width: 30, height: 30).clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                Text("Dayweave").font(Font2.serif(28)).foregroundStyle(Ink.primary)
            }
            Text(greeting).font(.system(size: 28, weight: .bold)).foregroundStyle(Ink.primary).padding(.top, 10)
            Text(Date().formatted(.dateTime.month().day().weekday(.wide)) + tr(" · 朝花未落，今天慢慢来", " · take the day slowly"))
                .font(.footnote).foregroundStyle(Ink.secondary)
        }
        .padding(.top, 8).padding(.horizontal, 4)
    }

    private var greeting: String {
        switch Calendar.current.component(.hour, from: Date()) {
        case 5..<11: tr("早上好，这是你的拾日", "Good morning")
        case 11..<14: tr("中午好", "Good afternoon")
        case 14..<18: tr("下午好", "Good afternoon")
        case 18..<23: tr("晚上好，夕拾时分", "Good evening")
        default: tr("夜深了", "It's late")
        }
    }
}

private struct EmptyHint: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(tr("从采集开始", "Start from Capture")).font(.headline)
            Text(tr("打开“采集”，拾日会安静地旁听，把对话写成文字。整理和提醒会出现在这里。", "Turn on Capture and Dayweave will quietly listen and write your conversations down. Digests and reminders appear here."))
                .font(.subheadline).foregroundStyle(Ink.secondary)
        }
        .card()
    }
}

/** 主动提醒卡：有时间的约定，可以提前 15 分钟提醒或加到日程。 */
struct SuggestionCard: View {
    let suggestion: Suggestion
    @Environment(Archive.self) private var archive
    @Environment(\.agent) private var agent
    @State private var added = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Image(systemName: suggestion.kind == "todo" ? "checklist" : suggestion.kind == "alarm" ? "alarm" : "calendar")
                    .font(.system(size: 17, weight: .medium)).foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(Ink.accent).shadow(color: Ink.accent.opacity(0.5), radius: 6, y: 4))
                VStack(alignment: .leading, spacing: 2) {
                    if let at = suggestion.at { Text(timeLabel(at)).font(.caption.weight(.semibold)).foregroundStyle(Ink.accent) }
                    Text(suggestion.title).font(.headline).foregroundStyle(Ink.primary)
                }
            }
            if !suggestion.why.isEmpty { Text(suggestion.why).font(.subheadline).foregroundStyle(Ink.primary).lineLimit(3) }
            if !suggestion.quote.isEmpty { Text("“\(suggestion.quote)”").font(.footnote.italic()).foregroundStyle(Ink.secondary).lineLimit(2) }
            HStack(spacing: 8) {
                if suggestion.at != nil {
                    Pill(title: tr("提前 15 分钟", "15 min early"), icon: "alarm", dark: true) { agent?.remind(suggestion) }
                    Pill(title: added ? tr("已加入", "Added") : tr("加到日程", "Calendar"), icon: "calendar") {
                        Task { added = await agent?.addToCalendar(suggestion) ?? false }
                    }
                }
                Spacer()
                Button(tr("忽略", "Dismiss")) { withAnimation(.spring) { archive.setStatus(suggestion.id, "dismissed") } }
                    .font(.subheadline).foregroundStyle(Ink.secondary)
            }
        }
        .card()
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(Ink.accent.opacity(0.45), lineWidth: 1))
    }
}

/** 一天的卡片：整理好的标题与小结，或者还在整理中。 */
struct DayCard: View {
    let day: String
    @Environment(Archive.self) private var archive
    @Environment(Digests.self) private var digests

    var body: some View {
        let segments = archive.segments(on: day)
        let digest = archive.digests[day]
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(dayTitle(day)).font(.system(size: 22, weight: .bold)).foregroundStyle(Ink.primary)
                    Text(tr("\(segments.count) 段对话 · ", "\(segments.count) conversations · ") + minutesText(segments.reduce(0) { $0 + $1.speechMs }))
                        .font(.caption).foregroundStyle(Ink.secondary)
                }
                Spacer()
                HStack(spacing: -7) {
                    ForEach(Array(Set(segments.flatMap(\.speakerIds))).sorted().prefix(4), id: \.self) { Face(speakerId: $0).overlay(Circle().stroke(Ink.veil, lineWidth: 2)) }
                }
            }
            if let digest {
                Text(digest.headline).font(.system(size: 18, weight: .semibold)).foregroundStyle(Ink.primary)
                Text(digest.summary).font(.subheadline).foregroundStyle(Ink.secondary).lineLimit(4)
                if let follow = digest.followUps?.first(where: { $0.owner == "me" }) ?? digest.followUps?.first {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(tr("要跟进", "Follow up")).font(.caption.weight(.semibold)).foregroundStyle(Ink.secondary)
                        Text(follow.text).font(.subheadline).foregroundStyle(Ink.primary)
                    }
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Ink.lavender.opacity(0.45)))
                }
            } else if digests.working.contains(day) {
                Shimmer()
            } else {
                Text(segments.last?.preview ?? "").font(.subheadline).foregroundStyle(Ink.secondary).lineLimit(3)
            }
        }
        .card()
        .multilineTextAlignment(.leading)
    }
}

func dayTitle(_ day: String) -> String {
    guard let date = DateFormatter.dayKey.date(from: day) else { return day }
    if Calendar.current.isDateInToday(date) { return tr("今天", "Today") }
    if Calendar.current.isDateInYesterday(date) { return tr("昨天", "Yesterday") }
    return date.formatted(.dateTime.month().day().weekday(.abbreviated))
}

extension DateFormatter {
    static let dayKey: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()
}

/** 整理中的占位：一道高光从左扫到右。 */
struct Shimmer: View {
    @State private var phase: CGFloat = -1
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach([0.9, 0.75, 0.5], id: \.self) { w in
                GeometryReader { geo in
                    RoundedRectangle(cornerRadius: 6).fill(Ink.tertiary.opacity(0.25)).frame(width: geo.size.width * w)
                        .overlay(LinearGradient(colors: [.clear, .white.opacity(0.5), .clear], startPoint: .leading, endPoint: .trailing)
                            .offset(x: phase * geo.size.width).mask(RoundedRectangle(cornerRadius: 6).frame(width: geo.size.width * w)))
                }
                .frame(height: 12)
            }
        }
        .onAppear { withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: false)) { phase = 1.2 } }
    }
}

/** 一天的完整整理与对话。 */
struct DayDetail: View {
    let day: String
    @Environment(Archive.self) private var archive
    @Environment(Digests.self) private var digests

    var body: some View {
        let digest = archive.digests[day]
        Screen(title: dayTitle(day), subtitle: digest?.headline) {
            if let digest {
                VStack(alignment: .leading, spacing: 10) {
                    Text(digest.summary).font(.body).foregroundStyle(Ink.primary)
                    if let topics = digest.topics, !topics.isEmpty {
                        Text(topics.map { "# \($0)" }.joined(separator: "  ")).font(.footnote).foregroundStyle(Ink.accent)
                    }
                }
                .card()
                group(tr("亮点", "Highlights"), digest.highlights?.map(\.text))
                group(tr("决定", "Decisions"), digest.decisions?.map(\.text))
                group(tr("要跟进", "Follow up"), digest.followUps?.map(\.text))
                group(tr("洞察", "Insights"), digest.insights?.map(\.text))
            }
            if let error = digests.errors[day] { Text(error).font(.footnote).foregroundStyle(Ink.danger) }
            HStack {
                Pill(title: digests.working.contains(day) ? tr("整理中…", "Writing…") : tr("重新整理", "Rewrite digest"), icon: "sparkles") { digests.refresh(day) }
                Spacer()
            }
            SectionLabel(text: tr("对话", "Conversations")).padding(.top, 6)
            ForEach(archive.segments(on: day)) { s in
                NavigationLink { ConversationView(id: s.id) } label: { SegmentRow(segment: s) }.buttonStyle(Pressable(scale: 0.98))
            }
        }
    }

    @ViewBuilder
    private func group(_ title: String, _ items: [String]?) -> some View {
        if let items, !items.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: title)
                ForEach(items, id: \.self) { item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Circle().fill(Ink.accent).frame(width: 5, height: 5)
                        Text(item).font(.subheadline).foregroundStyle(Ink.primary)
                    }
                }
            }
            .card()
        }
    }
}
