import SwiftUI

/** 三个标签：拾日（按天整理）、时间线（录了什么、说了什么）、采集（状态与服务）。选中只靠颜色区分，图标保持描边。 */
struct RootView: View {
    enum Tab: Hashable { case days, timeline, capture }
    @State private var tab: Tab = Self.initialTab
    @State private var showService = CommandLine.arguments.contains("-service")

    /** DEBUG 截图用：-tab timeline / capture。 */
    private static var initialTab: Tab {
        #if DEBUG
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "-tab"), i + 1 < args.count {
            return args[i + 1] == "timeline" ? .timeline : args[i + 1] == "capture" ? .capture : .days
        }
        #endif
        return .days
    }

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { DaysView() }
                .tabItem { Label(tr("拾日", "Today"), systemImage: "sun.max") }
                .tag(Tab.days)
            NavigationStack { TimelineScreen() }
                .tabItem { Label(tr("时间线", "Timeline"), systemImage: "text.alignleft") }
                .tag(Tab.timeline)
            NavigationStack { CaptureView().navigationDestination(isPresented: $showService) { ServiceView() } }
                .tabItem { Label(tr("采集", "Capture"), systemImage: "waveform") }
                .tag(Tab.capture)
        }
        .environment(\.symbolVariants, .none)
    }
}

/** 页面底色：柔光背景 + 大标题。 */
struct Screen<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.system(size: 32, weight: .bold)).foregroundStyle(Ink.primary)
                    if let subtitle { Text(subtitle).font(.subheadline).foregroundStyle(Ink.secondary) }
                }
                .padding(.top, 8).padding(.horizontal, 4)
                content
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background(Aurora())
        .toolbar(.hidden, for: .navigationBar)
    }
}
