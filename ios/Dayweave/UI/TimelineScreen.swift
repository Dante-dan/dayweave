import SwiftUI

/** 时间线：录了哪些声音、什么时候、谁说了什么。 */
struct TimelineScreen: View {
    @Environment(Archive.self) private var archive
    @Environment(Pipeline.self) private var pipeline

    var body: some View {
        let groups = Dictionary(grouping: archive.segments) { dayKey($0.start) }
        Screen(title: tr("时间线", "Timeline"), subtitle: tr("只保存文字，音频转写后即删除", "Only text is kept; audio is deleted after transcription")) {
            if archive.segments.isEmpty {
                Text(tr("还没有对话。打开“采集”开始旁听。", "No conversations yet. Turn on Capture to start listening."))
                    .font(.subheadline).foregroundStyle(Ink.secondary).card()
            }
            ForEach(groups.keys.sorted(by: >), id: \.self) { day in
                SectionLabel(text: dayTitle(day)).padding(.top, 8).padding(.horizontal, 4)
                ForEach(groups[day] ?? []) { s in
                    NavigationLink { ConversationView(id: s.id) } label: { SegmentRow(segment: s) }.buttonStyle(Pressable(scale: 0.98))
                }
            }
        }
    }
}

struct SegmentRow: View {
    let segment: Segment
    @Environment(Pipeline.self) private var pipeline

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(segment.start.formatted(date: .omitted, time: .shortened) + " – " + Date(ms: segment.endedAt).formatted(date: .omitted, time: .shortened))
                    .font(.subheadline.weight(.semibold)).foregroundStyle(Ink.primary)
                Text(tr("人声 ", "voice ") + minutesText(segment.speechMs)).font(.caption).foregroundStyle(Ink.secondary)
                if let scene = segment.scene {
                    Text(scene.kind.label).font(.caption2.weight(.semibold)).padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Capsule().fill(Ink.lavender.opacity(0.6))).foregroundStyle(Ink.primary)
                }
                Spacer()
                HStack(spacing: -6) { ForEach(segment.speakerIds.prefix(3), id: \.self) { Face(speakerId: $0, size: 22) } }
            }
            switch segment.status {
            case .DONE:
                Text(segment.preview.isEmpty ? tr("（没有识别出文字）", "(no words recognised)") : segment.preview)
                    .font(.subheadline).foregroundStyle(Ink.secondary).lineLimit(3).multilineTextAlignment(.leading)
            case .PENDING, .TRANSCRIBING:
                HStack(spacing: 6) {
                    ProgressView().controlSize(.mini)
                    Text(segment.error ?? tr("等待转写", "Waiting to transcribe")).font(.caption).foregroundStyle(Ink.secondary)
                }
            case .FAILED:
                HStack {
                    Text(segment.error ?? tr("转写失败", "Transcription failed")).font(.caption).foregroundStyle(Ink.danger)
                    Spacer()
                    Button(tr("重试", "Retry")) { pipeline.retry(segment.id) }.font(.caption.weight(.semibold))
                }
            }
        }
        .card(padding: 16)
    }
}

/** 一段对话的完整转写：按说话人分开，时间精确到秒。 */
struct ConversationView: View {
    let id: String
    @Environment(Archive.self) private var archive
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        if let s = archive.segment(id) {
            Screen(title: s.start.formatted(date: .abbreviated, time: .shortened), subtitle: tr("人声 ", "Voice ") + minutesText(s.speechMs) + " · " + s.device) {
                ForEach(Array(s.turns.enumerated()), id: \.offset) { _, t in
                    HStack(alignment: .top, spacing: 10) {
                        Face(speakerId: t.speakerId, size: 28)
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(speakerName(t.speakerId)).font(.caption.weight(.semibold)).foregroundStyle(Ink.primary)
                                Text(Date(ms: s.startedAt + t.startMs).formatted(date: .omitted, time: .standard)).font(.caption2).foregroundStyle(Ink.tertiary)
                            }
                            Text(t.text).font(.body).foregroundStyle(Ink.primary).textSelection(.enabled)
                        }
                    }
                    .card(padding: 14)
                }
                Button(role: .destructive) {
                    archive.delete(id)
                    dismiss()
                } label: {
                    Label(tr("删除这段对话", "Delete this conversation"), systemImage: "trash").font(.subheadline)
                }
                .padding(.top, 8)
            }
        }
    }
}
