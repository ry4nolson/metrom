import Foundation
import AVFoundation
import MetromShared

/// Int16 mono PCM sink @ 44.1kHz via AVAudioEngine.
///
/// `scheduleBuffer` does not block. Without pacing, the shared engine races ahead
/// of real time and fires beat UI callbacks in bursts (Android AudioTrack.write blocks).
final class AVAudioPcmSink: NSObject, AudioSink {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let previewPlayer = AVAudioPlayerNode()
    private var format: AVAudioFormat!
    private var framesWritten: Int64 = 0
    private var framesCompleted: Int64 = 0
    private let lock = NSLock()
    private var started = false
    private var loggedSampleRate = false
    /// Keep ~150ms queued ahead of the playback head.
    private let maxQueuedFrames: Int64 = 44100 * 150 / 1000
    private let headStallLimitSeconds: CFTimeInterval = 1.0

    override init() {
        super.init()
        engine.attach(player)
        engine.attach(previewPlayer)
        format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: 44100,
            channels: 1,
            interleaved: false
        )!
        engine.connect(player, to: engine.mainMixerNode, format: format)
        engine.connect(previewPlayer, to: engine.mainMixerNode, format: format)
    }

    /// Single definition of the playback AVAudioSession category.
    @discardableResult
    func activatePlaybackSession() -> Bool {
        do {
            let session = AVAudioSession.sharedInstance()
            // Preferences are requests only — ignore failures and continue.
            try? session.setPreferredSampleRate(44100)
            try? session.setPreferredIOBufferDuration(0.005)
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            lock.lock()
            let shouldLog = !loggedSampleRate
            if shouldLog { loggedSampleRate = true }
            lock.unlock()
            if shouldLog {
                let rate = session.sampleRate
                if abs(rate - 44100) > 0.5 {
                    NSLog("Metrom: warning session sampleRate=%.1f (preferred 44100)", rate)
                } else {
                    NSLog("Metrom: session sampleRate=%.1f", rate)
                }
            }
            return true
        } catch {
            NSLog("Metrom: activatePlaybackSession failed: \(error)")
            return false
        }
    }

    /// Record category for tempo detection. Caller must restore playback afterward.
    @discardableResult
    func activateRecordSession() -> Bool {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playAndRecord,
                mode: .measurement,
                options: [.defaultToSpeaker, .allowBluetooth]
            )
            try session.setActive(true)
            return true
        } catch {
            NSLog("Metrom: activateRecordSession failed: \(error)")
            return false
        }
    }

    func start(sampleRate: Int32, channelCount: Int32, preferredBufferFrames: Int32) -> Int32 {
        lock.lock()
        framesWritten = 0
        framesCompleted = 0
        player.stop()
        player.reset()
        lock.unlock()

        _ = activatePlaybackSession()
        do {
            if !engine.isRunning { try engine.start() }
            player.play()
            if !previewPlayer.isPlaying { previewPlayer.play() }
            lock.lock(); started = true; lock.unlock()
        } catch {
            lock.lock(); started = false; lock.unlock()
        }
        return max(preferredBufferFrames, 4410)
    }

    func write(pcm: KotlinShortArray, offset: Int32, count: Int32) -> Int32 {
        guard isStarted else { return -1 }
        let n = Int(count)
        guard n > 0 else { return 0 }

        var lastHead = playbackHeadFrames()
        var stallStarted: CFAbsoluteTime?
        while isStarted {
            let head = playbackHeadFrames()
            let queued = framesWrittenSnapshot - head
            if queued < maxQueuedFrames { break }
            if head > lastHead {
                lastHead = head
                stallStarted = nil
            } else {
                let now = CFAbsoluteTimeGetCurrent()
                if stallStarted == nil {
                    stallStarted = now
                } else if now - stallStarted! > headStallLimitSeconds {
                    return -1
                }
            }
            Thread.sleep(forTimeInterval: 0.002)
        }
        guard isStarted else { return -1 }

        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(n)) else {
            return -1
        }
        buffer.frameLength = AVAudioFrameCount(n)
        guard let dest = buffer.floatChannelData?[0] else { return -1 }
        for i in 0..<n {
            let sample = pcm.get(index: offset + Int32(i))
            dest[i] = Float(sample) / 32768.0
        }

        let frameCount = Int64(n)
        player.scheduleBuffer(buffer) { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.framesCompleted += frameCount
            self.lock.unlock()
        }

        lock.lock()
        framesWritten += frameCount
        lock.unlock()
        return Int32(n)
    }

    func playbackHeadFrames() -> Int64 {
        var playerHead: Int64?
        if let nodeTime = player.lastRenderTime, nodeTime.isSampleTimeValid,
           let playerTime = player.playerTime(forNodeTime: nodeTime),
           playerTime.isSampleTimeValid {
            playerHead = max(0, Int64(playerTime.sampleTime))
        }
        lock.lock()
        let completed = framesCompleted
        let written = framesWritten
        lock.unlock()
        // Prefer the renderer's sample clock; fall back to completed buffers only when unavailable.
        let head = playerHead ?? completed
        return min(head, written)
    }

    /// Schedule a one-shot preview on the shared engine (no new engine / sleep / session churn).
    func preview(pcm: KotlinShortArray) {
        let n = Int(pcm.size)
        guard n > 0 else { return }
        _ = activatePlaybackSession()
        do {
            if !engine.isRunning { try engine.start() }
            if !previewPlayer.isPlaying { previewPlayer.play() }
        } catch {
            return
        }
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(n)) else {
            return
        }
        buffer.frameLength = AVAudioFrameCount(n)
        guard let dest = buffer.floatChannelData?[0] else { return }
        for i in 0..<n {
            dest[i] = Float(pcm.get(index: Int32(i))) / 32768.0
        }
        previewPlayer.scheduleBuffer(buffer, completionHandler: nil)
    }

    func stop() {
        lock.lock()
        started = false
        lock.unlock()
        player.stop()
        player.reset()
        lock.lock()
        framesWritten = 0
        framesCompleted = 0
        lock.unlock()
    }

    func dispose() {
        stop()
        previewPlayer.stop()
        engine.stop()
    }

    func routeHint() -> AudioRouteHint {
        for o in AVAudioSession.sharedInstance().currentRoute.outputs {
            switch o.portType {
            case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE:
                return .bluetooth
            case .headphones, .headsetMic:
                return .wired
            case .usbAudio:
                return .usb
            case .builtInSpeaker, .builtInReceiver:
                return .speaker
            default:
                continue
            }
        }
        return .unknown
    }

    func setVolume(volume: Float) {
        player.volume = volume
    }

    func deactivateSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private var isStarted: Bool {
        lock.lock(); defer { lock.unlock() }
        return started
    }

    private var framesWrittenSnapshot: Int64 {
        lock.lock(); defer { lock.unlock() }
        return framesWritten
    }
}

final class IosEngineRunner: NSObject, EngineRunner {
    private let sink: AVAudioPcmSink
    private var thread: Thread?
    private var exitSemaphore: DispatchSemaphore?
    /// True from spawn until stopLocked finishes waiting for runLoop to unwind.
    private var running = false
    private let lock = NSLock()

    init(sink: AVAudioPcmSink) {
        self.sink = sink
        super.init()
    }

    func start(engine: MetronomeEngine) {
        lock.lock(); defer { lock.unlock() }
        if running { return }
        stopLocked(engine: engine)
        engine.markPlaying()
        let sem = DispatchSemaphore(value: 0)
        exitSemaphore = sem
        running = true
        let t = Thread {
            defer { sem.signal() }
            engine.runLoop()
        }
        t.name = "metrom-audio"
        t.qualityOfService = .userInteractive
        thread = t
        t.start()
    }

    func stop(engine: MetronomeEngine) {
        lock.lock(); defer { lock.unlock() }
        stopLocked(engine: engine)
    }

    func dispose(engine: MetronomeEngine) {
        lock.lock(); defer { lock.unlock() }
        stopLocked(engine: engine)
        sink.dispose()
    }

    func preview(engine: MetronomeEngine, accent: Bool) {
        let pcm = engine.resolvePreviewPcm(accent: accent)
        sink.preview(pcm: pcm)
    }

    func isRunning(engine: MetronomeEngine) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return running
    }

    /// Precondition: `lock` held. Waits for the audio thread with the lock held;
    /// the thread body only signals `exitSemaphore` and does not take `lock`.
    private func stopLocked(engine: MetronomeEngine) {
        engine.markStopped()
        if let sem = exitSemaphore {
            let result = sem.wait(timeout: .now() + 2.0)
            if result == .timedOut {
                NSLog("Metrom: IosEngineRunner stopLocked wait timed out")
            }
        }
        thread = nil
        exitSemaphore = nil
        running = false
    }
}

final class IosMicCapture: NSObject, MicCapture {
    private let sink: AVAudioPcmSink

    init(sink: AVAudioPcmSink) {
        self.sink = sink
        super.init()
    }

    func capture(
        seconds: Float,
        onProgress: @escaping (KotlinFloat) -> Void,
        isCancelled: @escaping () -> KotlinBoolean
    ) -> KotlinFloatArray? {
        let targetRate = 44100.0
        let discard = Int(targetRate * 0.3)
        let total = Int(targetRate * Double(seconds))
        var collected = [Float]()
        collected.reserveCapacity(total)

        guard sink.activateRecordSession() else { return nil }
        defer { _ = sink.activatePlaybackSession() }

        let eng = AVAudioEngine()
        let input = eng.inputNode
        let hwFormat = input.outputFormat(forBus: 0)
        NSLog("Metrom: mic hwFormat.sampleRate=%.1f", hwFormat.sampleRate)
        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: targetRate,
            channels: 1,
            interleaved: false
        ),
        let converter = AVAudioConverter(from: hwFormat, to: targetFormat) else {
            return nil
        }

        let semaphore = DispatchSemaphore(value: 0)
        var discarded = 0
        var failed = false
        let collectLock = NSLock()
        var totalInputFrames: Int64 = 0
        var totalOutputFrames: Int64 = 0
        var convertErrors = 0

        input.installTap(onBus: 0, bufferSize: 1024, format: hwFormat) { buffer, _ in
            if isCancelled().boolValue {
                semaphore.signal()
                return
            }

            collectLock.lock()
            totalInputFrames += Int64(buffer.frameLength)
            collectLock.unlock()

            let ratio = targetRate / hwFormat.sampleRate
            let outFrames = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 32
            guard let converted = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: outFrames) else {
                return
            }

            var provided = false
            var convertError: NSError?
            let inputBlock: AVAudioConverterInputBlock = { _, outStatus in
                if provided {
                    outStatus.pointee = .noDataNow
                    return nil
                }
                provided = true
                outStatus.pointee = .haveData
                return buffer
            }
            converter.convert(to: converted, error: &convertError, withInputFrom: inputBlock)
            if convertError != nil {
                collectLock.lock()
                convertErrors += 1
                collectLock.unlock()
                return
            }
            guard let ch = converted.floatChannelData?[0] else { return }
            let frames = Int(converted.frameLength)

            collectLock.lock()
            totalOutputFrames += Int64(converted.frameLength)
            for i in 0..<frames {
                if discarded < discard {
                    discarded += 1
                    continue
                }
                if collected.count >= total { break }
                collected.append(ch[i])
            }
            let progress = Float(collected.count) / Float(total)
            let done = collected.count >= total
            collectLock.unlock()

            onProgress(KotlinFloat(float: min(1, progress)))
            if done {
                semaphore.signal()
            }
        }

        do {
            try eng.start()
        } catch {
            failed = true
            semaphore.signal()
        }

        _ = semaphore.wait(timeout: .now() + Double(seconds) + 2.0)
        input.removeTap(onBus: 0)
        eng.stop()

        collectLock.lock()
        let snapshot = collected
        let inFrames = totalInputFrames
        let outFrames = totalOutputFrames
        let errors = convertErrors
        collectLock.unlock()

        let measuredRatio = inFrames > 0 ? Double(outFrames) / Double(inFrames) : 0.0
        NSLog(
            "Metrom: mic converter in=%lld out=%lld ratio=%.5f errors=%d",
            inFrames, outFrames, measuredRatio, errors
        )

        if failed || isCancelled().boolValue || snapshot.count < total {
            return nil
        }

        let arr = KotlinFloatArray(size: Int32(snapshot.count))
        for (i, v) in snapshot.enumerated() {
            arr.set(index: Int32(i), value: v)
        }
        return arr
    }
}
