import SwiftUI

/** 手动组装的依赖：个人规模的 App 不需要 DI 框架。 */
@MainActor
final class AppModel {
    let archive = Archive()
    let cloud = CloudAPI()
    let recorder: Recorder
    let pipeline: Pipeline
    let digests: Digests
    let agent: Agent
    let subscriptions: Subscriptions

    init() {
        recorder = Recorder(archive: archive)
        pipeline = Pipeline(archive: archive, cloud: cloud)
        digests = Digests(archive: archive, cloud: cloud)
        agent = Agent(archive: archive, cloud: cloud)
        subscriptions = Subscriptions(cloud: cloud)
        recorder.onSegment = { [weak self] _ in self?.pipeline.kick() }
        pipeline.onDone = { [weak self] segment in self?.agent.consider(segment) }
        #if DEBUG
        // 模拟器自测：SIMCTL_CHILD_DAYWEAVE_LICENSE 写入授权码，-autostart 启动后直接旁听
        if let code = ProcessInfo.processInfo.environment["DAYWEAVE_LICENSE"] { cloud.licenseCode = code }
        if CommandLine.arguments.contains("-demo") {
            Demo.seed(archive)
            // 标准方案，本月用掉 8.5 小时
            cloud.demoAccount = CloudAccount(label: "sub:plus", resetsAt: Date().addingTimeInterval(19 * 86400).ms, grantedMicros: 30 * 60 * 80_800,
                                             balanceMicros: Int64(21.5 * 60) * 80_800, priceMicrosPerMinute: 80_800, monthSeconds: 8 * 3600 + 30 * 60)
            Task { await cloud.refresh() }
        }
        if CommandLine.arguments.contains("-autostart") { Task { await recorder.start() } }
        if let path = ProcessInfo.processInfo.environment["DAYWEAVE_FEED"] { Task { await recorder.feed(URL(fileURLWithPath: path)) } }
        #endif
    }

    /** 回到前台：刷新额度、续上转写队列；订阅每月续费后，把最新的交易交给拾日云开启新周期。 */
    func becameActive() async {
        if cloud.licenseCode?.hasPrefix("DS-") == true { await subscriptions.restore(quiet: true) }
        await cloud.refresh()
        pipeline.kick()
    }
}

@main
struct DayweaveApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model.archive)
                .environment(model.cloud)
                .environment(model.recorder)
                .environment(model.pipeline)
                .environment(model.digests)
                .environment(model.subscriptions)
                .environment(\.agent, model.agent)
                .tint(Ink.accent)
        }
        .onChange(of: phase) { _, now in
            if now == .active { Task { await model.becameActive() } }
        }
    }
}

private struct AgentKey: EnvironmentKey {
    static let defaultValue: Agent? = nil
}

extension EnvironmentValues {
    var agent: Agent? {
        get { self[AgentKey.self] }
        set { self[AgentKey.self] = newValue }
    }
}
