import Foundation
import Observation

/** 订阅的额度与用量。金额单位是百万分之一美元，界面上只换算成时长显示。label 为 sub:<方案>。 */
struct CloudAccount: Codable, Hashable {
    var label: String? = ""
    var expiresAt: Int64? = nil
    /** 下次发放时长的时间：月付为续费日，年付每 30 天一次。 */
    var resetsAt: Int64? = nil
    var grantedMicros: Int64? = 0
    var balanceMicros: Int64
    var priceMicrosPerMinute: Int64
    var monthSeconds: Int64? = 0
    var monthMicros: Int64? = 0

    var minutesLeft: Int64 { priceMicrosPerMinute > 0 ? balanceMicros / priceMicrosPerMinute : 0 }
    var totalMinutes: Int64 { priceMicrosPerMinute > 0 ? (grantedMicros ?? 0) / priceMicrosPerMinute : 0 }
    var plan: Plan? { Plan.allCases.first { "sub:\($0.rawValue)" == label } }
}

struct CloudError: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}

enum CloudStatus: Equatable {
    case unknown, loading, noLicense
    case ready(CloudAccount)
    case failed(String)
}

/**
 * 拾日云：转写和整理用我们提供的密钥。没有账户：App Store 订阅核验后换来 DS-… 凭证，存在钥匙串里。
 * 有效时长由服务端按收到的音频长度计算；App 在本地已经去掉了静音和环境音。
 */
@MainActor
@Observable
final class CloudAPI {
    nonisolated static let base: String = (Bundle.main.object(forInfoDictionaryKey: "DayweaveCloudURL") as? String ?? "")
        .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    nonisolated static var available: Bool { !base.isEmpty }

    private(set) var status: CloudStatus = .unknown

    var licenseCode: String? {
        get { Keychain.get("license_code") }
        set { Keychain.set("license_code", newValue) }
    }

    var account: CloudAccount? { if case let .ready(a) = status { a } else { nil } }

    /** 不能转写的原因；可以转写时为 nil。 */
    var problem: String? {
        if !Self.available { return tr("这个版本没有接入拾日云", "This build isn't connected to Dayweave Cloud") }
        if (licenseCode ?? "").isEmpty { return tr("订阅拾日云后开始转写", "Subscribe to Dayweave Cloud to start transcribing") }
        if let a = account, a.balanceMicros <= 0 {
            return tr("本月时长已用完", "This month's hours are used up")
        }
        return nil
    }

    #if DEBUG
    /** 上架截图：固定显示一个示例账户，不访问服务端。 */
    var demoAccount: CloudAccount?
    #endif

    func refresh() async {
        #if DEBUG
        if let demoAccount { status = .ready(demoAccount); return }
        #endif
        guard Self.available else { return }
        guard let code = licenseCode, !code.isEmpty else { status = .noLicense; return }
        if account == nil { status = .loading }
        do {
            status = .ready(try await Self.me(code))
        } catch let e as CloudError {
            status = .failed(e.status == 401 ? tr("请恢复购买", "Please restore purchases")
                : e.status == 402 ? tr("订阅已到期，续订或恢复购买后继续", "Subscription ended. Renew or restore purchases to continue")
                : tr("连接失败", "Couldn't connect"))
        } catch {
            status = .failed(tr("连接失败", "Couldn't connect"))
        }
    }

    /** 把 App Store 的签名交易交给服务端核验，换回拾日云凭证。成功返回 nil。 */
    func subscribe(signedTransaction: String) async -> String? {
        struct Reply: Decodable { let licenseCode: String }
        do {
            let data = try await Self.request("POST", "/v1/subscription", body: ["store": "apple", "signedTransaction": signedTransaction], timeout: 60)
            licenseCode = try JSONDecoder().decode(Reply.self, from: data).licenseCode
            await refresh()
            return nil
        } catch let e as CloudError {
            return e.status == 402 ? tr("订阅已到期", "This subscription has ended") : tr("订阅核验失败（\(e.status)）", "Couldn't verify the subscription (\(e.status))")
        } catch {
            return tr("网络不可用", "No network")
        }
    }

    func signOut() {
        licenseCode = nil
        status = .noLicense
    }

    // MARK: 转写与整理（可在后台调用）

    nonisolated static func me(_ code: String) async throws -> CloudAccount {
        try JSONDecoder().decode(CloudAccount.self, from: try await request("GET", "/v1/me", code: code))
    }

    /** 16 kHz 16-bit 单声道 PCM，≤ 60 秒。返回 Speech-to-Text v2 的原始结果。 */
    nonisolated static func transcribe(_ pcm: Data, code: String) async throws -> Data {
        try await request("POST", "/v1/transcribe", body: ["audio": pcm.base64EncodedString()], code: code, timeout: 120)
    }

    /** 每日整理（digest）或主动提醒（agent）：提示词在服务端，只发任务类型、语言和转写内容。 */
    nonisolated static func assist(_ kind: String, input: String, code: String) async throws -> String {
        struct Reply: Decodable { let text: String }
        let data = try await request("POST", "/v1/assist", body: ["kind": kind, "lang": Lang.english ? "en" : "zh", "input": input], code: code, timeout: 180)
        return try JSONDecoder().decode(Reply.self, from: data).text
    }

    nonisolated private static func request(_ method: String, _ path: String, body: [String: String]? = nil, code: String? = nil, timeout: TimeInterval = 20) async throws -> Data {
        var req = URLRequest(url: URL(string: base + path)!, timeoutInterval: timeout)
        req.httpMethod = method
        if let code { req.setValue("Bearer \(code)", forHTTPHeaderField: "Authorization") }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw CloudError(status: status, message: message ?? "HTTP \(status)")
        }
        return data
    }
}
