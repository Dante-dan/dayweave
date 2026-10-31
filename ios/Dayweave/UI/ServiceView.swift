import StoreKit
import SwiftUI

let SITE = "https://dayweave.duanjl.com"

/** 拾日云：iOS 只通过 App Store 订阅；显示本月剩余时长和计费方式。 */
struct ServiceView: View {
    @Environment(CloudAPI.self) private var cloud
    @Environment(Subscriptions.self) private var subs

    var body: some View {
        Screen(title: tr("拾日云", "Dayweave Cloud"), subtitle: tr("转写和整理由拾日云完成，只计人声时长", "Dayweave Cloud transcribes and writes digests; only voice counts")) {
            if let account = cloud.account { BalanceCard(account: account) }
            if case let .failed(message) = cloud.status {
                Text(message).font(.subheadline).foregroundStyle(Ink.danger).card()
            }
            PlansCard()
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: tr("怎么计费", "How billing works"))
                Text(tr("只有送去转写的人声才计入时长。静音和环境音在手机上就被过滤；音乐、播客和视频不录、不收费。每日整理和主动提醒包含在内。",
                        "Only voice that's sent for transcription counts. Silence and room noise are filtered on the phone; music, podcasts and video are never recorded or billed. Digests and reminders are included."))
                    .font(.footnote).foregroundStyle(Ink.secondary)
            }
            .card()
        }
        .task {
            await subs.load()
            await cloud.refresh()
        }
    }
}

private struct BalanceCard: View {
    let account: CloudAccount
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(tr("拾日云 · \(account.plan?.label ?? "")", "Dayweave Cloud · \(account.plan?.label ?? "")")).font(.caption.weight(.semibold)).foregroundStyle(Ink.secondary)
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                Text(hours(account.minutesLeft)).font(.system(size: 40, weight: .bold)).foregroundStyle(Ink.primary)
                Text("/ " + hours(account.totalMinutes)).font(.subheadline).foregroundStyle(Ink.secondary)
            }
            if let end = account.resetsAt ?? account.expiresAt {
                Text(tr("本月剩余人声时长 · \(Date(ms: end).formatted(.dateTime.month().day())) 重新发放", "Voice left this month · refills \(Date(ms: end).formatted(.dateTime.month().day()))"))
                    .font(.footnote.weight(.medium)).foregroundStyle(Ink.primary)
            }
            HStack(spacing: 10) {
                stat(tr("本月有效转录", "Voice this month"), minutesText((account.monthSeconds ?? 0) * 1000))
            }
            .padding(.top, 6)
        }
        .card(padding: 22)
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(LinearGradient(colors: [Ink.peach, Ink.accent.opacity(0.6), Ink.lavender], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1.5))
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption2).foregroundStyle(Ink.secondary)
            Text(value).font(.subheadline.weight(.semibold)).foregroundStyle(Ink.primary)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Ink.lavender.opacity(0.45)))
    }
}

/**
 * 订阅方案。价格取自 App Store（本地化）；按 App 审核要求，在购买处写清周期、自动续期和条款链接。
 * 当前方案只用颜色区分。
 */
private struct PlansCard: View {
    @Environment(CloudAPI.self) private var cloud
    @Environment(Subscriptions.self) private var subs
    @Environment(\.openURL) private var openURL
    @State private var yearly = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel(text: tr("订阅拾日云", "Subscribe to Dayweave Cloud"))
            Picker("", selection: $yearly.animation(.spring(response: 0.35, dampingFraction: 0.8))) {
                Text(tr("月付", "Monthly")).tag(false)
                Text(tr("年付 · 送 2 个月", "Yearly · 2 months free")).tag(true)
            }
            .pickerStyle(.segmented)
            ForEach(Plan.allCases.filter { !yearly || $0.hasYearly }) { plan in
                let current = cloud.account?.plan == plan
                let key = PlanKey(plan: plan, yearly: yearly)
                let product = subs.products[key]
                Button {
                    Task { await subs.purchase(key) }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(plan.label).font(.headline).foregroundStyle(current ? Ink.accent : Ink.primary)
                            Text(tr("每月 \(plan.hours) 小时人声 · \(plan.perDay)", "\(plan.hours) h of voice a month · \(plan.perDay)"))
                                .font(.caption).foregroundStyle(Ink.secondary)
                        }
                        Spacer()
                        Text(current ? tr("当前方案", "Current") : (product?.displayPrice ?? plan.usd(yearly: yearly)) + (yearly ? tr(" / 年", " / year") : tr(" / 月", " / month")))
                            .font(.subheadline.weight(.semibold)).foregroundStyle(current ? Ink.accent : Ink.primary)
                    }
                    .padding(.horizontal, 14).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(current ? Ink.accent.opacity(0.12) : Ink.veil.opacity(0.55)))
                }
                .buttonStyle(Pressable(scale: 0.98))
                .disabled(product == nil || current || subs.busy)
            }
            Text(tr("自动续期（按月或按年），可随时在“设置 › Apple ID › 订阅”中取消，需在续期前至少 24 小时取消。时长每 30 天发放一次，未用完的不结转。",
                    "Renews automatically (monthly or yearly) until cancelled. Cancel any time in Settings › Apple ID › Subscriptions at least 24 hours before renewal. Hours refill every 30 days and don't roll over."))
                .font(.caption2).foregroundStyle(Ink.tertiary)
            HStack(spacing: 14) {
                Pill(title: subs.busy ? tr("核验中…", "Verifying…") : tr("恢复购买", "Restore purchases")) { Task { await subs.restore() } }
                Button(tr("使用条款", "Terms of Use")) { openURL(URL(string: "\(SITE)\(Lang.english ? "" : "/zh")/terms/")!) }.font(.caption)
                Button(tr("隐私政策", "Privacy Policy")) { openURL(URL(string: "\(SITE)\(Lang.english ? "" : "/zh")/privacy/")!) }.font(.caption)
            }
            if let message = subs.message { Text(message).font(.footnote).foregroundStyle(Ink.secondary) }
        }
        .card()
    }
}

private func hours(_ minutes: Int64) -> String { minutes >= 60 ? String(format: "%.1f h", Double(minutes) / 60) : "\(minutes) min" }
