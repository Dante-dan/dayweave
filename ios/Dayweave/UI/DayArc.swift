import SwiftUI

/**
 * 一天的日轨：太阳沿二次贝塞尔从日出（6:00）走到此刻，点亮走过的轨迹；
 * 每隔几秒一片花瓣从太阳处落下，轨迹是抛物线 y = y0 + ½gτ²。进入时太阳按阻尼弹簧的解析解就位。
 */
struct DayArc: View {
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var appeared = Date()

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 60, paused: reduce)) { ctx in
            Canvas { g, size in
                let e = ctx.date.timeIntervalSince(appeared)
                draw(&g, size: size, elapsed: reduce ? 10 : e)
            }
        }
        .frame(height: 92)
        .accessibilityHidden(true)
    }

    /** x(t) = 1 − e^(−ζωt)(cos ω_d t + ζω/ω_d sin ω_d t) */
    private func spring(_ t: Double, w: Double = 6, z: Double = 0.9) -> Double {
        let wd = w * (1 - z * z).squareRoot()
        return 1 - exp(-z * w * t) * (cos(wd * t) + z * w / wd * sin(wd * t))
    }

    private func draw(_ g: inout GraphicsContext, size: CGSize, elapsed: Double) {
        let p0 = CGPoint(x: 14, y: size.height - 12), p2 = CGPoint(x: size.width - 14, y: size.height - 12)
        let p1 = CGPoint(x: size.width / 2, y: -size.height * 0.95)
        func bez(_ u: Double) -> CGPoint {
            let a = (1 - u) * (1 - u), b = 2 * (1 - u) * u, c = u * u
            return CGPoint(x: a * p0.x + b * p1.x + c * p2.x, y: a * p0.y + b * p1.y + c * p2.y)
        }
        var path = Path()
        path.move(to: p0)
        path.addQuadCurve(to: p2, control: p1)
        g.stroke(path, with: .color(Ink.tertiary.opacity(0.7)), style: StrokeStyle(lineWidth: 1.2, dash: [2, 5]))

        let now = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let target = min(0.96, max(0.05, (Double(now.hour ?? 12) + Double(now.minute ?? 0) / 60 - 6) / 12))
        let u = target * min(1.02, spring(elapsed * 0.55))
        let lit = path.trimmedPath(from: 0, to: u)
        var glow = g
        glow.addFilter(.shadow(color: Ink.petal.opacity(0.8), radius: 4))
        glow.stroke(lit, with: .color(Ink.petal), style: StrokeStyle(lineWidth: 2.4, lineCap: .round))

        let sun = bez(u)
        var halo = g
        halo.addFilter(.shadow(color: Ink.sun, radius: 8))
        halo.fill(Path(ellipseIn: CGRect(x: sun.x - 5, y: sun.y - 5, width: 10, height: 10)), with: .color(.white))

        // 月亮在暮色一端
        let moon = CGPoint(x: p2.x + 2, y: p2.y - 16)
        var crescent = Path(ellipseIn: CGRect(x: moon.x - 7, y: moon.y - 7, width: 14, height: 14))
        crescent = crescent.subtracting(Path(ellipseIn: CGRect(x: moon.x - 3, y: moon.y - 10, width: 14, height: 14)))
        g.fill(crescent, with: .color(Color(red: 0.81, green: 0.77, blue: 1)))

        // 落瓣：带缺口的樱花瓣，绕自身转动
        let tau = (elapsed.truncatingRemainder(dividingBy: 6)) / 6
        let px = sun.x + 70 * tau, py = sun.y + 120 * tau * tau
        guard py < size.height, !reduce else { return }
        var petal = g
        petal.opacity = sin(.pi * min(1, tau * 1.3))
        petal.translateBy(x: px, y: py)
        petal.rotate(by: .degrees(tau * 300))
        var shape = Path()
        shape.move(to: CGPoint(x: 0, y: 5))
        shape.addCurve(to: CGPoint(x: -1.2, y: -5), control1: CGPoint(x: -4.5, y: 2), control2: CGPoint(x: -4, y: -4))
        shape.addLine(to: CGPoint(x: 0, y: -3.9))
        shape.addLine(to: CGPoint(x: 1.2, y: -5))
        shape.addCurve(to: CGPoint(x: 0, y: 5), control1: CGPoint(x: 4, y: -4), control2: CGPoint(x: 4.5, y: 2))
        petal.fill(shape, with: .color(Ink.petal))
    }
}
