import SwiftUI

/** 采集：一颗会呼吸的光球（随电平起伏）、当前听到了什么、服务与用量入口。 */
struct CaptureView: View {
    @Environment(Recorder.self) private var recorder
    @Environment(CloudAPI.self) private var cloud
    @Environment(Pipeline.self) private var pipeline
    @Environment(Archive.self) private var archive
    @Environment(\.agent) private var agent

    var body: some View {
        Screen(title: tr("采集", "Capture"), subtitle: tr("安静地旁听，只留下人声", "Listening quietly, keeping only voices")) {
            VStack(spacing: 18) {
                Orb(running: recorder.running, level: recorder.level, speaking: recorder.hearingSpeech) {
                    Task {
                        if recorder.running { recorder.stop() } else {
                            agent?.requestNotifications()
                            await recorder.start()
                        }
                    }
                }
                .frame(height: 240)
                Text(statusText).font(.headline).foregroundStyle(Ink.primary).contentTransition(.opacity)
                    .animation(.easeInOut(duration: 0.25), value: statusText)
                if let problem = recorder.problem ?? (recorder.running ? cloud.problem : nil) {
                    Text(problem).font(.footnote).foregroundStyle(Ink.danger).multilineTextAlignment(.center)
                }
                LevelBar(level: recorder.level).frame(height: 6).padding(.horizontal, 24)
            }
            .card(padding: 22)

            let pending = archive.segments.filter { $0.status == .PENDING || $0.status == .TRANSCRIBING }.count
            VStack(alignment: .leading, spacing: 10) {
                row("mic", tr("输入", "Input"), tr("iPhone 内置麦克风", "iPhone built-in microphone"))
                row("music.note", tr("播放的声音", "Playing audio"), tr("音乐、播客、视频不录，只在有人说话时记下场景", "Music, podcasts and video aren't recorded; only the scene is noted when someone talks"))
                row("arrow.up.circle", tr("待转写", "Waiting"), pending == 0 ? tr("没有", "Nothing") : tr("\(pending) 段", "\(pending) segments"))
            }
            .card()

            NavigationLink { ServiceView() } label: {
                HStack(spacing: 12) {
                    Image(systemName: "cloud").font(.system(size: 18)).foregroundStyle(Ink.accent).frame(width: 36, height: 36)
                        .background(Circle().fill(Ink.accent.opacity(0.12)))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tr("拾日云订阅", "Dayweave Cloud")).font(.headline).foregroundStyle(Ink.primary)
                        Text(serviceSummary).font(.caption).foregroundStyle(Ink.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(Ink.tertiary)
                }
                .card()
            }
            .buttonStyle(Pressable(scale: 0.98))

            Text(tr("请只在身边的人知情且愿意的场合记录。旁听时状态栏会显示麦克风正在使用。", "Please record only where people around you are aware and comfortable. iOS shows the microphone indicator while Dayweave listens."))
                .font(.caption).foregroundStyle(Ink.tertiary).padding(.horizontal, 4)
        }
    }

    private var statusText: String {
        if !recorder.running { return tr("轻点光球开始旁听", "Tap the orb to start listening") }
        if recorder.inSegment { return tr("正在记录这段对话", "Writing down this conversation") }
        if recorder.hearingMusic { return tr("在放音乐，不录", "Music playing — not recording") }
        return tr("安静地听着", "Listening quietly")
    }

    private var serviceSummary: String {
        switch cloud.status {
        case let .ready(a):
            return tr("\(a.plan?.label ?? "拾日云") · 本月剩余 \(a.minutesLeft) 分钟", "\(a.plan?.label ?? "Cloud") · \(a.minutesLeft) min left this month")
        case .noLicense: return tr("订阅后开始转写", "Subscribe to start transcribing")
        case let .failed(m): return m
        default: return tr("拾日云", "Dayweave Cloud")
        }
    }

    private func row(_ icon: String, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.system(size: 15)).foregroundStyle(Ink.primary).frame(width: 28, height: 28)
                .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Ink.veil.opacity(0.8)))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.medium)).foregroundStyle(Ink.primary)
                Text(detail).font(.caption).foregroundStyle(Ink.secondary)
            }
        }
    }
}

/**
 * 光球：静止时缓慢呼吸（正弦），旁听时半径跟随电平，用临界阻尼弹簧追随，听到人声时外圈泛起玫瑰色光晕。
 */
private struct Orb: View {
    let running: Bool
    let level: Float
    let speaking: Bool
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var r: Double = 0
    @State private var v: Double = 0
    @State private var last = Date()

    var body: some View {
        Button(action: action) {
            TimelineView(.animation(paused: reduce)) { ctx in
                let t = ctx.date.timeIntervalSinceReferenceDate
                let breathe = 0.04 * sin(t * 1.6)
                let target = running ? Double(max(0, min(1, (level + 60) / 45))) : 0
                let scale = 0.78 + breathe + 0.22 * advance(to: target, now: ctx.date)
                ZStack {
                    Circle().fill(Ink.accent.opacity(speaking ? 0.28 : 0.10)).scaleEffect(scale * 1.25).blur(radius: 24)
                    Circle()
                        .fill(RadialGradient(colors: [.white, running ? Ink.petal : Ink.lavender, running ? Ink.accent : Ink.secondary.opacity(0.6)],
                                             center: UnitPoint(x: 0.35, y: 0.3), startRadius: 4, endRadius: 120))
                        .overlay(Circle().stroke(.white.opacity(0.7), lineWidth: 1))
                        .shadow(color: Ink.shadow.opacity(0.35), radius: 24, y: 14)
                        .scaleEffect(scale)
                    Image(systemName: running ? "pause" : "mic")
                        .font(.system(size: 34, weight: .light)).foregroundStyle(.white)
                }
                .frame(width: 180, height: 180)
            }
        }
        .buttonStyle(Pressable(scale: 0.94))
        .accessibilityLabel(running ? tr("暂停旁听", "Pause listening") : tr("开始旁听", "Start listening"))
    }

    /** 临界阻尼弹簧：a = ω²(target − r) − 2ωv。只在绘制时推进，不触发额外刷新。 */
    private func advance(to target: Double, now: Date) -> Double {
        let dt = min(0.05, now.timeIntervalSince(last))
        let w = 9.0
        let a = w * w * (target - r) - 2 * w * v
        DispatchQueue.main.async {
            v += a * dt
            r += v * dt
            last = now
        }
        return r
    }
}

private struct LevelBar: View {
    let level: Float
    var body: some View {
        GeometryReader { geo in
            let f = CGFloat(max(0, min(1, (level + 70) / 60)))
            ZStack(alignment: .leading) {
                Capsule().fill(Ink.tertiary.opacity(0.25))
                Capsule().fill(LinearGradient(colors: [Ink.petal, Ink.accent], startPoint: .leading, endPoint: .trailing))
                    .frame(width: geo.size.width * f)
                    .animation(.spring(response: 0.25, dampingFraction: 0.8), value: f)
            }
        }
    }
}
