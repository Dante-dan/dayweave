import Foundation
import Observation

private let CHUNK_MAX = SAMPLE_RATE * 55 // 同步识别单次上限 60 秒，留余量

/**
 * 转写队列：片段按时间顺序逐个送到拾日云，文字保存后删除临时音频。
 * 失败时保留音频稍后重试；额度用完或没有开通时停下，等用户处理。
 */
@MainActor
@Observable
final class Pipeline {
    private(set) var working = false
    private let archive: Archive
    private let cloud: CloudAPI
    var onDone: ((Segment) -> Void)?

    init(archive: Archive, cloud: CloudAPI) {
        self.archive = archive
        self.cloud = cloud
    }

    func kick() {
        guard !working else { return }
        working = true
        Task {
            defer { working = false }
            while let next = archive.segments.filter({ $0.status == .PENDING }).min(by: { $0.startedAt < $1.startedAt }) {
                guard cloud.problem == nil, let code = cloud.licenseCode else { return }
                if await !process(next, code: code) { return }
            }
        }
    }

    func retry(_ id: String) {
        guard var s = archive.segment(id), FileManager.default.fileExists(atPath: archive.audioURL(id).path) else { return }
        s.status = .PENDING
        s.error = nil
        archive.upsert(s)
        kick()
    }

    /** 返回 false 表示应该停下（网络或额度问题），稍后再试。 */
    private func process(_ segment: Segment, code: String) async -> Bool {
        var s = segment
        s.status = .TRANSCRIBING
        archive.upsert(s)
        let url = archive.audioURL(s.id)
        do {
            let (pcm, offsets) = try readPcm(url)
            var turns: [Turn] = []
            var languages: [String] = []
            var from = 0
            while from < pcm.count {
                let to = min(pcm.count, from + CHUNK_MAX)
                let data = pcm[from..<to].withUnsafeBufferPointer { Data(buffer: $0) }
                let reply = try await CloudAPI.transcribe(data, code: code)
                let recognition = parseRecognition(reply)
                languages += recognition.languages
                let baseMs = Int64(from * 1000 / SAMPLE_RATE)
                turns += recognition.turns(offsetMs: baseMs) { ms in
                    // 只留人声的音频时间 → 原始时间：每 100 ms 一帧，查它在原始时间轴上的序号
                    let frame = Int(ms / 100)
                    guard frame < offsets.count else { return ms }
                    return Int64(offsets[frame]) * 100 + ms % 100
                }
                from = to
            }
            s.turns = mergeTurns(turns)
            s.languages = Array(Set(languages))
            s.status = .DONE
            s.error = nil
            archive.upsert(s)
            try? FileManager.default.removeItem(at: url)
            await cloud.refresh()
            onDone?(s)
            return true
        } catch let e as CloudError {
            s.status = e.status == 402 || e.status == 401 ? .PENDING : .FAILED
            s.error = e.message
            archive.upsert(s)
            await cloud.refresh()
            return false
        } catch {
            s.status = .PENDING
            s.error = error.localizedDescription
            archive.upsert(s)
            return false
        }
    }
}

struct Word { let startMs: Int64; let endMs: Int64; let text: String; let label: String }

struct Recognition {
    var words: [Word]
    var languages: [String]

    /** 按说话人标签把词连成发言。标签只在一次识别内有效，记作“说话人 N”。 */
    func turns(offsetMs: Int64, toOriginal: (Int64) -> Int64) -> [Turn] {
        var out: [Turn] = []
        for w in words {
            let speaker = "speaker-\(w.label)"
            let start = toOriginal(offsetMs + w.startMs), end = toOriginal(offsetMs + w.endMs)
            if var last = out.last, last.speakerId == speaker {
                last.text = joinText(last.text, w.text)
                last.endMs = end
                out[out.count - 1] = last
            } else {
                out.append(Turn(speakerId: speaker, startMs: start, endMs: end, text: w.text))
            }
        }
        return out
    }
}

/** 解析 Speech-to-Text v2 的识别结果（拾日云原样转发）。 */
func parseRecognition(_ data: Data) -> Recognition {
    guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return Recognition(words: [], languages: []) }
    var words: [Word] = []
    var languages: [String] = []
    for case let result as [String: Any] in root["results"] as? [Any] ?? [] {
        if let lang = result["languageCode"] as? String { languages.append(lang) }
        guard let alt = (result["alternatives"] as? [[String: Any]])?.first else { continue }
        for w in alt["words"] as? [[String: Any]] ?? [] {
            words.append(Word(
                startMs: offsetMs(w["startOffset"] as? String), endMs: offsetMs(w["endOffset"] as? String),
                text: w["word"] as? String ?? "", label: w["speakerLabel"] as? String ?? "0",
            ))
        }
    }
    return Recognition(words: words, languages: languages)
}

/** "1.240s" → 1240 */
private func offsetMs(_ value: String?) -> Int64 {
    guard let v = value?.replacingOccurrences(of: "s", with: ""), let d = Double(v) else { return 0 }
    return Int64(d * 1000)
}

/** 中文之间不加空格，英文单词之间加。与安卓 joinText 相同。 */
func joinText(_ left: String, _ right: String) -> String {
    let r = right.trimmingCharacters(in: .whitespaces)
    guard let a = left.last else { return r }
    guard let b = r.first else { return left }
    let latin: (Character) -> Bool = { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "'") }
    return latin(a) && latin(b) || (",.!?;:".contains(a) && latin(b)) ? "\(left) \(r)" : left + r
}

func mergeTurns(_ turns: [Turn]) -> [Turn] {
    turns.reduce(into: []) { acc, t in
        if var last = acc.last, last.speakerId == t.speakerId, t.startMs - last.endMs < 1500 {
            last.text = joinText(last.text, t.text)
            last.endMs = t.endMs
            acc[acc.count - 1] = last
        } else {
            acc.append(t)
        }
    }
}

/** “说话人 1”：iOS 版还没有声纹，按识别结果的标签编号。 */
func speakerName(_ id: String) -> String {
    let n = (Int(id.replacingOccurrences(of: "speaker-", with: "")) ?? 0) + 1
    return tr("说话人 \(n)", "Speaker \(n)")
}
