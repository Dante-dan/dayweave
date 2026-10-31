import Foundation
import Observation
import StoreKit

/** 拾日云订阅方案。商品 ID 与 Google Play、服务端 plans.mjs 一致：app.dayweave.cloud.<档位>.<monthly|yearly>。 */
enum Plan: String, CaseIterable, Identifiable {
    case lite, plus, pro, max

    var id: String { rawValue }
    func productId(yearly: Bool) -> String { "app.dayweave.cloud.\(rawValue).\(yearly ? "yearly" : "monthly")" }
    var hours: Int { switch self { case .lite: 5; case .plus: 30; case .pro: 100; case .max: 280 } }
    /** 年付 = 10 个月的价格；专业和全天只有月付（App Store 订阅单价上限 $1,000）。 */
    var hasYearly: Bool { self == .lite || self == .plus }
    func usd(yearly: Bool) -> String {
        switch self {
        case .lite: yearly ? "$199.99" : "$19.99"
        case .plus: yearly ? "$999.99" : "$99.99"
        case .pro: "$199.99"
        case .max: "$499.99"
        }
    }
    var label: String {
        switch self {
        case .lite: tr("轻享", "Lite")
        case .plus: tr("标准", "Plus")
        case .pro: tr("专业", "Pro")
        case .max: tr("全天", "Max")
        }
    }

    /** 按 30 天平均到每天的人声时长。 */
    var perDay: String {
        let m = hours * 2
        guard m >= 60 else { return tr("约每天 \(m) 分钟", "~\(m) min a day") }
        let h = String(format: "%g", (Double(m) / 6).rounded() / 10)
        return tr("约每天 \(h) 小时", "~\(h) h a day")
    }

    static func of(productId: String) -> Plan? { allCases.first { productId.hasPrefix("app.dayweave.cloud.\($0.rawValue).") } }
}

struct PlanKey: Hashable { let plan: Plan; let yearly: Bool }

/**
 * App Store 订阅（StoreKit 2）。购买、续费或恢复时，把签名交易（JWS）交给拾日云离线核验，
 * 换回凭证（DS-…），之后和授权码走同一套额度。
 */
@MainActor
@Observable
final class Subscriptions {
    private(set) var products: [PlanKey: Product] = [:]
    private(set) var busy = false
    var message: String?

    private let cloud: CloudAPI
    private var updates: Task<Void, Never>?

    init(cloud: CloudAPI) {
        self.cloud = cloud
        // 续费、在其他设备购买、家庭共享、退款等都会从这里到达
        updates = Task { [weak self] in
            for await result in Transaction.updates {
                guard let self, case let .verified(tx) = result else { continue }
                await self.redeem(result.jwsRepresentation, quiet: true)
                await tx.finish()
            }
        }
    }

    func load() async {
        do {
            let list = try await Product.products(for: Plan.allCases.flatMap { [$0.productId(yearly: false)] + ($0.hasYearly ? [$0.productId(yearly: true)] : []) })
            products = Dictionary(uniqueKeysWithValues: list.compactMap { p in Plan.of(productId: p.id).map { (PlanKey(plan: $0, yearly: p.id.hasSuffix(".yearly")), p) } })
        } catch {
            products = [:]
        }
    }

    func purchase(_ key: PlanKey) async {
        guard let product = products[key] else { return }
        message = nil
        do {
            switch try await product.purchase() {
            case let .success(result):
                if case let .verified(tx) = result {
                    await redeem(result.jwsRepresentation, quiet: false)
                    await tx.finish()
                } else {
                    message = tr("购买凭证无法验证", "The purchase couldn't be verified")
                }
            case .pending:
                message = tr("付款处理中，完成后会自动开通", "Payment is pending; Cloud turns on once it clears")
            case .userCancelled:
                break
            @unknown default:
                break
            }
        } catch {
            message = tr("购买没有完成", "Purchase didn't go through")
        }
    }

    /** 恢复购买：先与 App Store 同步，再把当前有效的订阅交给拾日云。 */
    func restore(quiet: Bool = false) async {
        if !quiet { try? await AppStore.sync() }
        var latest: VerificationResult<Transaction>?
        for await result in Transaction.currentEntitlements {
            guard case let .verified(tx) = result, tx.productType == .autoRenewable else { continue }
            if latest == nil || (try? latest!.payloadValue.purchaseDate) ?? .distantPast < tx.purchaseDate { latest = result }
        }
        guard let latest else {
            if !quiet { message = tr("这个 Apple ID 下没有拾日云订阅", "No Dayweave Cloud subscription on this Apple ID") }
            return
        }
        await redeem(latest.jwsRepresentation, quiet: quiet)
    }

    private func redeem(_ jws: String, quiet: Bool) async {
        busy = true
        let error = await cloud.subscribe(signedTransaction: jws)
        busy = false
        if error != nil || !quiet { message = error ?? tr("拾日云已开通", "Dayweave Cloud is on") }
    }
}
