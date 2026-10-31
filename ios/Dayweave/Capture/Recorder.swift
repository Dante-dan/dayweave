import AVFoundation
import Observation
import SoundAnalysis
import os

private let log = Logger(subsystem: "app.dayweave", category: "capture")

let SAMPLE_RATE = 16_000
private let FRAME_SAMPLES = SAMPLE_RATE / 10 // 100 ms
private let PRE_ROLL_FRAMES = 10 // 1 s，避免漏掉讲话开头
private let START_FRAMES = 3 // 连续 300 ms 人声才开段
private let END_SILENCE_FRAMES = 25 // 2.5 s 没有人声就结束
private let MAX_SEGMENT_FRAMES = 15 * 60 * 10 // 单段最长 15 分钟
private let MIN_SPEECH_FRAMES = 8 // 少于 0.8 s 人声的片段丢掉
private let HANGOVER_FRAMES = 4 // 人声后多留 400 ms，句尾不被截断
private let SILENT_DB: Float = -60

/**
 * 安静的旁听者：用内置麦克风持续收音，不抢耳机、不打断正在播放的音乐。
 * 系统自带的声音分类器（SoundAnalysis）判断“是不是有人在说话”；只有人声帧被留下写进片段，
 * 静音、环境音和外放的音乐不会离开手机，也不计费。
 */
@MainActor
@Observable
final class Recorder {
    private(set) var running = false
    private(set) var inSegment = false
    private(set) var level: Float = -80 // dBFS，给界面画电平
    private(set) var hearingSpeech = false
    private(set) var hearingMusic = false
    var problem: String?

    /** 片段写完后交给转写。 */
    var onSegment: ((Segment) -> Void)?

    private let archive: Archive
    private let engine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private var analyzer: SNAudioStreamAnalyzer?
    private let analysisQueue = DispatchQueue(label: "dayweave.analysis")
    private let classifier = SpeechClassifier()

    // 分段状态（只在主线程上改）
    private var pending: [Int16] = []
    private var preRoll: [[Int16]] = []
    private var voicedRun = 0
    private var silence = 0
    private var segmentFrames = 0
    private var speechFrames = 0
    private var hangover = 0
    private var kept: [Int16] = []
    private var keptOffsets: [Int32] = [] // 每个留下的帧在原始时间轴上的序号，用于还原发言时间
    private var segmentStart = Date()
    private var preRollCount = 0
    private var frameCount = 0
    private var segmentScene: AudioScene?

    init(archive: Archive) {
        self.archive = archive
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated { self?.interrupted(note) }
        }
    }

    func start() async {
        guard !running else { return }
        log.info("capture starting")
        guard await AVAudioApplication.requestRecordPermission() else {
            log.error("no mic permission")
            problem = tr("需要麦克风权限才能旁听", "Dayweave needs microphone access to listen")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            // mixWithOthers：不打断音乐和播客；不加 allowBluetooth：不把耳机切到通话模式
            try session.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .defaultToSpeaker, .allowBluetoothA2DP])
            try session.setActive(true)
            if let builtIn = session.availableInputs?.first(where: { $0.portType == .builtInMic }) { try? session.setPreferredInput(builtIn) }

            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(SAMPLE_RATE), channels: 1, interleaved: true)!
            converter = AVAudioConverter(from: format, to: target)
            let analyzer = SNAudioStreamAnalyzer(format: format)
            let request = try SNClassifySoundRequest(classifierIdentifier: .version1)
            request.windowDuration = CMTime(seconds: 1.0, preferredTimescale: 1000)
            request.overlapFactor = 0.5
            try analyzer.add(request, withObserver: classifier)
            self.analyzer = analyzer

            input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, when in
                guard let self else { return }
                self.analysisQueue.async { analyzer.analyze(buffer, atAudioFramePosition: when.sampleTime) }
                let samples = self.convert(buffer, to: target)
                Task { @MainActor in self.accept(samples) }
            }
            engine.prepare()
            try engine.start()
            running = true
            problem = nil
            log.info("capture started, input \(format.sampleRate) Hz × \(format.channelCount)")
        } catch {
            log.error("capture failed: \(error.localizedDescription)")
            problem = tr("麦克风无法启动：\(error.localizedDescription)", "Couldn't start the microphone: \(error.localizedDescription)")
            stop()
        }
    }

    #if DEBUG
    /** 模拟器自测：把音频文件按实时速度送进同一条链路（分类 → 分段 → 转写），末尾补 4 秒静音让片段收尾。 */
    func feed(_ url: URL) async {
        guard let file = try? AVAudioFile(forReading: url) else { log.error("feed: can't open"); return }
        let format = file.processingFormat
        let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(SAMPLE_RATE), channels: 1, interleaved: true)!
        converter = AVAudioConverter(from: format, to: target)
        let analyzer = SNAudioStreamAnalyzer(format: format)
        guard let request = try? SNClassifySoundRequest(classifierIdentifier: .version1) else { return }
        request.windowDuration = CMTime(seconds: 1.0, preferredTimescale: 1000)
        request.overlapFactor = 0.5
        try? analyzer.add(request, withObserver: classifier)
        running = true
        var position: AVAudioFramePosition = 0
        let silence = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4096)!
        silence.frameLength = 4096
        let total = file.length + AVAudioFramePosition(format.sampleRate * 4)
        while position < total {
            let buffer: AVAudioPCMBuffer
            if position < file.length {
                buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4096)!
                try? file.read(into: buffer, frameCount: 4096)
            } else {
                buffer = silence
            }
            let at = position
            analysisQueue.sync { analyzer.analyze(buffer, atAudioFramePosition: at) }
            accept(convert(buffer, to: target))
            position += AVAudioFramePosition(buffer.frameLength)
            try? await Task.sleep(for: .seconds(Double(buffer.frameLength) / format.sampleRate))
        }
        if inSegment { close() }
        running = false
        log.info("feed done")
    }
    #endif

    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        analyzer?.removeAllRequests()
        analyzer = nil
        if inSegment { close() }
        running = false
        hearingSpeech = false
        level = -80
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func interrupted(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        // 来电或其他 App 占用麦克风时先收尾，结束后自动接着听
        if type == .began, running { stop(); resumeAfterInterruption = true }
        if type == .ended, resumeAfterInterruption { resumeAfterInterruption = false; Task { await start() } }
    }
    private var resumeAfterInterruption = false

    nonisolated private func convert(_ buffer: AVAudioPCMBuffer, to format: AVAudioFormat) -> [Int16] {
        guard let converter = MainActor.assumeIsolated({ self.converter }) else { return [] }
        let ratio = format.sampleRate / buffer.format.sampleRate
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 32) else { return [] }
        var fed = false
        converter.convert(to: out, error: nil) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true
            status.pointee = .haveData
            return buffer
        }
        guard let data = out.int16ChannelData else { return [] }
        return Array(UnsafeBufferPointer(start: data[0], count: Int(out.frameLength)))
    }

    private func accept(_ samples: [Int16]) {
        pending.append(contentsOf: samples)
        while pending.count >= FRAME_SAMPLES {
            let frame = Array(pending.prefix(FRAME_SAMPLES))
            pending.removeFirst(FRAME_SAMPLES)
            frameReady(frame)
        }
    }

    private func frameReady(_ frame: [Int16]) {
        let db = dbfs(frame)
        level = level * 0.6 + db * 0.4
        hearingSpeech = classifier.speech
        hearingMusic = classifier.music
        let voice = classifier.speech && db > SILENT_DB
        #if DEBUG
        frameCount += 1
        if frameCount % 20 == 0 { log.debug("level \(db, format: .fixed(precision: 1)) dB speech \(self.classifier.speech) music \(self.classifier.music) seg \(self.inSegment)") }
        #endif

        if !inSegment {
            preRoll.append(frame)
            if preRoll.count > PRE_ROLL_FRAMES { preRoll.removeFirst() }
            voicedRun = voice ? voicedRun + 1 : 0
            if voicedRun >= START_FRAMES { open() }
            return
        }

        segmentFrames += 1
        if voice {
            silence = 0
            speechFrames += 1
            hangover = HANGOVER_FRAMES
        } else {
            silence += 1
        }
        // 只留下人声（和句尾的一点余音），静音和环境音不写进片段
        if voice || hangover > 0 {
            if !voice { hangover -= 1 }
            kept.append(contentsOf: frame)
            keptOffsets.append(Int32(preRollCount + segmentFrames - 1))
        }
        if silence >= END_SILENCE_FRAMES || segmentFrames >= MAX_SEGMENT_FRAMES { close() }
    }

    private func open() {
        inSegment = true
        preRollCount = preRoll.count
        segmentStart = Date().addingTimeInterval(-Double(preRollCount) / 10)
        segmentFrames = 0
        speechFrames = START_FRAMES
        silence = 0
        hangover = HANGOVER_FRAMES
        kept = []
        keptOffsets = []
        for (i, f) in preRoll.enumerated() {
            kept.append(contentsOf: f)
            keptOffsets.append(Int32(i))
        }
        preRoll = []
        voicedRun = 0
        segmentScene = AVAudioSession.sharedInstance().isOtherAudioPlaying ? AudioScene(kind: .MEDIA) : nil
    }

    private func close() {
        inSegment = false
        defer { kept = []; keptOffsets = [] }
        guard speechFrames >= MIN_SPEECH_FRAMES else { return }
        let id = "seg-\(Date().ms)"
        let end = segmentStart.addingTimeInterval(Double(segmentFrames + preRollCount) / 10)
        do {
            try writePcm(kept, offsets: keptOffsets, to: archive.audioURL(id))
        } catch {
            return
        }
        let segment = Segment(
            id: id, startedAt: segmentStart.ms, endedAt: end.ms, source: .MIC, device: tr("iPhone 麦克风", "iPhone mic"),
            speechMs: Int64(kept.count * 1000 / SAMPLE_RATE), scene: segmentScene,
        )
        archive.upsert(segment)
        onSegment?(segment)
    }
}

/** 系统声音分类结果：最近一个窗口里“说话”和“音乐”的把握。 */
final class SpeechClassifier: NSObject, SNResultsObserving, @unchecked Sendable {
    private let lock = NSLock()
    private var _speech = false
    private var _music = false
    var speech: Bool { lock.withLock { _speech } }
    var music: Bool { lock.withLock { _music } }

    func request(_ request: SNRequest, didProduce result: SNResult) {
        guard let result = result as? SNClassificationResult else { return }
        let speech = ["speech", "conversation", "narration_monologue", "shout", "whispering", "laughter"]
            .compactMap { result.classification(forIdentifier: $0)?.confidence }.max() ?? 0
        let music = ["music", "singing"].compactMap { result.classification(forIdentifier: $0)?.confidence }.max() ?? 0
        // 外放的歌里也有人声：音乐更强时不算人在说话
        lock.withLock {
            _speech = speech > 0.5 && speech > music * 0.9
            _music = music > 0.5
        }
    }
}

func dbfs(_ frame: [Int16]) -> Float {
    guard !frame.isEmpty else { return -80 }
    var sum: Float = 0
    for s in frame { let v = Float(s) / 32768; sum += v * v }
    let rms = (sum / Float(frame.count)).squareRoot()
    return rms > 0 ? max(-80, 20 * log10(rms)) : -80
}

/**
 * 临时音频：16 kHz 16-bit 单声道 PCM，后面附上每个留下的 100 ms 帧在原始时间轴上的序号，
 * 转写后用来把发言时间还原到真实时刻。文件头 8 字节：样本数、序号数。
 */
func writePcm(_ samples: [Int16], offsets: [Int32], to url: URL) throws {
    var data = Data()
    var n = UInt32(samples.count), m = UInt32(offsets.count)
    data.append(Data(bytes: &n, count: 4))
    data.append(Data(bytes: &m, count: 4))
    samples.withUnsafeBufferPointer { data.append(Data(buffer: $0)) }
    offsets.withUnsafeBufferPointer { data.append(Data(buffer: $0)) }
    try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
}

func readPcm(_ url: URL) throws -> (samples: [Int16], offsets: [Int32]) {
    let data = try Data(contentsOf: url)
    guard data.count >= 8 else { throw CocoaError(.fileReadCorruptFile) }
    let n = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt32.self) })
    let m = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 4, as: UInt32.self) })
    guard data.count >= 8 + n * 2 + m * 4 else { throw CocoaError(.fileReadCorruptFile) }
    let samples = data.subdata(in: 8..<(8 + n * 2)).withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }
    let offsets = data.subdata(in: (8 + n * 2)..<(8 + n * 2 + m * 4)).withUnsafeBytes { Array($0.bindMemory(to: Int32.self)) }
    return (samples, offsets)
}
