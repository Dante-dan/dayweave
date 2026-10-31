import SwiftUI

// 朝花夕拾：浅色为朝、深色为夕。光影而非彩色渐变：顶光、下暗、柔和投影。与安卓 ui/theme 的色值一致。

extension Color {
    init(light: UInt32, dark: UInt32) {
        self.init(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

enum Ink {
    static let base = Color(light: 0xFBF8FC, dark: 0x0E0C14)
    static let primary = Color(light: 0x17131F, dark: 0xF3F0F8)
    static let secondary = Color(light: 0x6E6880, dark: 0xA9A3B8)
    static let tertiary = Color(light: 0xA39DB2, dark: 0x6F6880)
    static let accent = Color(light: 0xE34F86, dark: 0xFF8DB6)
    static let danger = Color(light: 0xED4956, dark: 0xFF6B78)
    static let good = Color(red: 0.18, green: 0.71, blue: 0.49)
    static let peach = Color(light: 0xFFD9C7, dark: 0x3A2230)
    static let pink = Color(light: 0xFFC9E0, dark: 0x3B1C38)
    static let lavender = Color(light: 0xDCD2FF, dark: 0x26214D)
    static let sky = Color(light: 0xC9E6FF, dark: 0x12263F)
    static let veil = Color(light: 0xFFFFFF, dark: 0x2B2737)
    static let shadow = Color(light: 0x6B4E9B, dark: 0x000000)
    static let sun = Color(red: 1, green: 0.77, blue: 0.6)
    static let petal = Color(red: 0.97, green: 0.66, blue: 0.76)
    static let speakers: [Color] = [0x7B61FF, 0x00A3A3, 0xFF7A59, 0x3D8BFD, 0xE0A100, 0x9C5DE0, 0x2EB67D, 0xE5537A].map { Color(UIColor(hex: $0)) }
}

enum Font2 {
    static func serif(_ size: CGFloat) -> Font { .system(size: size, weight: .regular, design: .serif).italic() }
}

/** 背景：四团柔光沿李萨如曲线缓慢漂移。 */
struct Aurora: View {
    @Environment(\.accessibilityReduceMotion) private var reduce
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: reduce)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            GeometryReader { geo in
                let w = geo.size.width, h = geo.size.height
                ZStack {
                    Ink.base
                    blob(Ink.peach, size: w * 1.2, x: -w * 0.3 + 40 * sin(0.11 * t), y: -h * 0.25 + 30 * sin(0.07 * t))
                    blob(Ink.pink, size: w * 1.1, x: w * 0.45 + 36 * sin(0.08 * t + 1.3), y: -h * 0.05 + 50 * sin(0.13 * t))
                    blob(Ink.lavender, size: w * 1.3, x: -w * 0.1 + 60 * sin(0.06 * t + 2.1), y: h * 0.45 + 36 * sin(0.09 * t))
                    blob(Ink.sky, size: w, x: w * 0.4 + 30 * sin(0.09 * t + 3.7), y: h * 0.85 + 44 * sin(0.05 * t)).opacity(0.7)
                }
            }
        }
        .ignoresSafeArea()
    }

    private func blob(_ color: Color, size: CGFloat, x: CGFloat, y: CGFloat) -> some View {
        Circle().fill(color).frame(width: size, height: size).blur(radius: 70).offset(x: x, y: y)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/** 玻璃：iOS 26 用系统的 Liquid Glass，早期系统用材质 + 高光描边 + 柔和投影模拟。 */
struct GlassModifier<S: Shape>: ViewModifier {
    let shape: S
    var tint: Color? = nil
    var interactive = false

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(interactive ? Glass.regular.tint(tint).interactive() : Glass.regular.tint(tint), in: shape)
        } else {
            content
                .background(.ultraThinMaterial, in: shape)
                .background((tint ?? .clear).opacity(0.25), in: shape)
                .overlay(shape.stroke(LinearGradient(colors: [.white.opacity(0.75), .white.opacity(0.08)], startPoint: .top, endPoint: .bottom), lineWidth: 1))
                .shadow(color: Ink.shadow.opacity(0.18), radius: 18, y: 10)
        }
    }
}

extension View {
    func glass<S: Shape>(_ shape: S, tint: Color? = nil, interactive: Bool = false) -> some View {
        modifier(GlassModifier(shape: shape, tint: tint, interactive: interactive))
    }

    func card(padding: CGFloat = 18) -> some View {
        self.padding(padding).frame(maxWidth: .infinity, alignment: .leading).glass(RoundedRectangle(cornerRadius: 26, style: .continuous))
    }
}

/** 按下时按弹簧缩放，松开回弹。 */
struct Pressable: ButtonStyle {
    var scale: CGFloat = 0.96
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

/** 胶囊按钮：dark 为主操作（实底），否则为玻璃。 */
struct Pill: View {
    let title: String
    var icon: String? = nil
    var dark = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon { Image(systemName: icon).font(.system(size: 13, weight: .semibold)) }
                Text(title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
            }
            .fixedSize()
            .padding(.horizontal, 14).padding(.vertical, 9)
            .foregroundStyle(dark ? Ink.base : Ink.primary)
            .background {
                if dark { Capsule().fill(Ink.primary) }
            }
            .modifier(OptionalGlass(enabled: !dark))
        }
        .buttonStyle(Pressable())
    }
}

private struct OptionalGlass: ViewModifier {
    let enabled: Bool
    func body(content: Content) -> some View {
        if enabled { content.glass(Capsule(), interactive: true) } else { content }
    }
}

struct SectionLabel: View {
    let text: String
    var body: some View {
        Text(text).font(.system(size: 12, weight: .semibold)).foregroundStyle(Ink.secondary).textCase(.uppercase).tracking(0.6)
    }
}

/** 说话人头像：同一个人在各处同色。 */
struct Face: View {
    let speakerId: String
    var size: CGFloat = 26
    var body: some View {
        let n = Int(speakerId.replacingOccurrences(of: "speaker-", with: "")) ?? 0
        Text("\(n + 1)")
            .font(.system(size: size * 0.42, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Circle().fill(Ink.speakers[n % Ink.speakers.count]))
    }
}

func minutesText(_ ms: Int64) -> String {
    let m = ms / 60_000
    if m < 1 { return tr("\(max(1, ms / 1000)) 秒", "\(max(1, ms / 1000))s") }
    return tr("\(m) 分钟", "\(m) min")
}
