import Foundation
import AVFoundation
import MediaPlayer
import UIKit
import MetromShared

/// Bridges shared MetronomeController to SwiftUI + AVAudio + Now Playing.
final class MetronomeBridge: ObservableObject {
    @Published private(set) var bpm: Int = 120
    @Published private(set) var isPlaying: Bool = false
    @Published private(set) var statusLine: String = "READY"
    @Published private(set) var meterLabel: String = "4/4"
    @Published private(set) var subdivisionLabel: String = "×1"
    @Published private(set) var swingLabel: String = "Off"
    @Published private(set) var toneLabel: String = "Wood"
    @Published private(set) var volume: Float = 0.9
    @Published private(set) var muted: Bool = false
    @Published private(set) var hapticsOn: Bool = true
    @Published private(set) var activeBeat: Int = -1
    @Published private(set) var beatAccents: [BeatAccentLevel] = [.strong, .normal, .normal, .normal]
    @Published private(set) var countInBars: Int = 1
    @Published private(set) var muteLabel: String = "Off"
    @Published private(set) var trainerEnabled: Bool = false
    @Published private(set) var trainerTarget: Int = 120
    @Published private(set) var listenOptions: [Int] = []
    @Published private(set) var listenStatus: String = ""
    @Published private(set) var songs: [SongRow] = []
    @Published private(set) var groupTempo: Bool = false
    @Published private(set) var accentNoteLabel: String = "A4"
    @Published private(set) var restNoteLabel: String = "Off"
    @Published private(set) var sessionPhase: String = "IDLE"
    @Published private(set) var tapHint: String? = nil
    @Published private(set) var beatFlash: Int64 = 0
    @Published private(set) var beatAtMs: Int64 = 0
    @Published private(set) var isAccentBeat: Bool = false
    @Published private(set) var meterOptions: [String] = []
    @Published private(set) var subdivisionOptions: [String] = []
    @Published private(set) var swingOptions: [String] = []
    @Published private(set) var toneOptions: [String] = []
    @Published private(set) var noteOptions: [String] = []
    @Published private(set) var muteOptions: [String] = []

    enum BeatAccentLevel: String {
        case strong, normal, mute
    }

    struct SongRow: Identifiable {
        let id: String
        let name: String
    }

    private let controller: MetronomeController
    private let engine: MetronomeEngine
    private let sink: AVAudioPcmSink
    private let runner: IosEngineRunner
    private var pollTimer: Timer?
    private var optionsLoaded = false
    private var interruptedWhilePlaying = false
    private var interruptionObserver: NSObjectProtocol?
    private var routeChangeObserver: NSObjectProtocol?

    private var ui: MetronomeUiState? {
        controller.state.value as? MetronomeUiState
    }

    init() {
        let prefs = IosPrefsStore()
        let assets = IosAssetIO()
        let cache = SampleToneCache(assets: assets)
        sink = AVAudioPcmSink()
        let clock = IosUiClock()
        let latency = IosLatencyPad()
        let haptics = IosHaptics()
        let mic = IosMicCapture(sink: sink)

        engine = MetronomeEngine(
            sink: sink,
            clock: clock,
            latencyPad: latency,
            sampleCache: cache,
            onBeat: { _ in }
        )

        runner = IosEngineRunner(sink: sink)

        let audioSink = sink
        controller = MetronomeController(
            prefs: prefs,
            haptics: haptics,
            sampleCache: cache,
            engine: engine,
            runner: runner,
            micCapture: mic,
            canStart: {
                KotlinBoolean(bool: audioSink.activatePlaybackSession())
            },
            onPlaybackChanged: { playing, bpm, subtitle in
                DispatchQueue.main.async {
                    MetronomeBridge.publishNowPlaying(
                        playing: playing.boolValue,
                        bpm: bpm.intValue,
                        subtitle: subtitle
                    )
                }
            },
            onTrainerAutoStopped: {
                DispatchQueue.main.async {
                    audioSink.deactivateSession()
                }
            }
        )

        // onBeat is posted via IosUiClock onto the main queue — refresh immediately
        // so beatFlash is not quantized to the poll timer.
        engine.onBeat = { [weak self] event in
            self?.controller.handleBeat(event: event)
            self?.refreshFromState()
        }

        setupRemoteCommands()
        setupAudioSessionObservers()
        refreshFromState()
        startPolling()
    }

    deinit {
        pollTimer?.invalidate()
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
        }
        if let routeChangeObserver {
            NotificationCenter.default.removeObserver(routeChangeObserver)
        }
    }

    func togglePlay() {
        if isPlaying { stop() } else { start() }
    }

    func start() {
        interruptedWhilePlaying = false
        _ = sink.activatePlaybackSession()
        controller.start()
        refreshFromState()
    }

    func stop() {
        interruptedWhilePlaying = false
        controller.stop()
        refreshFromState()
    }

    func nudgeBpm(_ delta: Int32) {
        controller.nudgeBpm(delta: delta)
        refreshFromState()
    }

    func setBpm(_ value: Int32) {
        controller.setBpm(bpm: value, persist: true)
        refreshFromState()
    }

    func tapTempo() {
        controller.tapTempo(nowMs: Int64(Date().timeIntervalSince1970 * 1000))
        refreshFromState()
    }

    func toggleMute() { controller.toggleMute(); refreshFromState() }
    func toggleHaptics() { controller.toggleHaptics(); refreshFromState() }
    func setVolume(_ v: Float) { controller.setVolume(volume: v); refreshFromState() }

    func selectMeter(_ label: String) {
        guard let ts = TimeSignature.companion.COMMON.first(where: { $0.label == label }) else { return }
        controller.setTimeSignature(signature: ts)
        refreshFromState()
    }

    func selectSubdivision(_ label: String) {
        let all = Subdivision.values()
        for i in 0..<Int(all.size) {
            if let s = all.get(index: Int32(i)) as? Subdivision, s.label == label {
                controller.setSubdivision(subdivision: s)
                refreshFromState()
                return
            }
        }
    }

    func selectSwing(_ label: String) {
        let all = SwingFeel.values()
        for i in 0..<Int(all.size) {
            if let s = all.get(index: Int32(i)) as? SwingFeel, s.label == label {
                controller.setSwing(feel: s)
                refreshFromState()
                return
            }
        }
    }

    func selectTone(_ label: String) {
        guard let tone = ui?.toneOptions.first(where: { $0.label == label }) else { return }
        controller.setTone(tone: tone, preview: true)
        refreshFromState()
    }

    func cycleBeatAccent(_ index: Int32) {
        controller.cycleBeatAccent(index: index)
        refreshFromState()
    }

    func resetBeatAccents() {
        controller.resetBeatAccents()
        refreshFromState()
    }

    func setCountIn(_ bars: Int32) {
        controller.setCountInBars(bars: bars)
        refreshFromState()
    }

    func selectMutePattern(_ label: String) {
        guard let pattern = MutePattern.companion.OPTIONS.first(where: { $0.label == label }) else { return }
        controller.setMutePattern(pattern: pattern)
        refreshFromState()
    }

    func toggleTrainer() { controller.toggleTrainer(); refreshFromState() }
    func cycleTrainerTarget() { controller.cycleTrainerTarget(); refreshFromState() }
    func saveSong() { controller.saveCurrentSong(name: nil); refreshFromState() }

    func loadSong(id: String) {
        if let song = ui?.songs.first(where: { $0.id == id }) {
            controller.loadSong(song: song)
        }
        refreshFromState()
    }

    func deleteSong(id: String) {
        if let song = ui?.songs.first(where: { $0.id == id }) {
            controller.deleteSong(song: song)
        }
        refreshFromState()
    }

    func startListen() {
        listenStatus = "Listening…"
        controller.listenCaptureRunner = { [weak self] mic in
            DispatchQueue.global(qos: .userInitiated).async {
                self?.controller.runListenCapture(mic: mic)
                DispatchQueue.main.async { self?.refreshFromState() }
            }
        }
        controller.startListen()
        refreshFromState()
    }

    func applyListenBpm(_ bpm: Int32) {
        controller.applyListenBpm(bpm: bpm)
        refreshFromState()
    }

    func resetListen() { controller.resetListen(); refreshFromState() }
    func toggleGroupTempo() { controller.toggleGroupTempo(); refreshFromState() }

    func selectAccentNote(_ label: String) {
        let all = AccentNote.values()
        for i in 0..<Int(all.size) {
            if let n = all.get(index: Int32(i)) as? AccentNote, n.label == label {
                controller.setAccentNote(note: n, preview: true)
                refreshFromState()
                return
            }
        }
    }

    func selectRestNote(_ label: String) {
        let all = AccentNote.values()
        for i in 0..<Int(all.size) {
            if let n = all.get(index: Int32(i)) as? AccentNote, n.label == label {
                controller.setRestNote(note: n, preview: true)
                refreshFromState()
                return
            }
        }
    }

    private func startPolling() {
        // 4 Hz backstop for non-beat state (songs, trainer, detect). Beat flash is pushed
        // immediately from engine.onBeat on the main queue.
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            guard let self else { return }
            // Surface async engine death (write stall / session failure) that left UI playing.
            if let s = self.controller.state.value as? MetronomeUiState,
               s.isPlaying,
               !self.runner.isRunning(engine: self.engine) {
                self.controller.handleEngineFailed()
            }
            self.refreshFromState()
        }
        RunLoop.main.add(timer, forMode: .common)
        pollTimer = timer
    }

    private func refreshFromState() {
        guard let s = ui else { return }

        assignIfChanged(&bpm, Int(s.bpm))
        assignIfChanged(&isPlaying, s.isPlaying)
        assignIfChanged(&statusLine, s.statusLine)
        assignIfChanged(&meterLabel, s.timeSignature.label)
        assignIfChanged(&subdivisionLabel, s.subdivision.label)
        assignIfChanged(&swingLabel, s.swing.label)
        assignIfChanged(&toneLabel, s.tone.label)
        if abs(volume - s.volume) > 0.001 { volume = s.volume }
        assignIfChanged(&muted, s.muted)
        assignIfChanged(&hapticsOn, s.haptics)
        assignIfChanged(&activeBeat, Int(s.activeBeat))
        assignIfChanged(&countInBars, Int(s.countInBars))
        assignIfChanged(&muteLabel, s.mutePattern.label)
        assignIfChanged(&trainerEnabled, s.trainerEnabled)
        assignIfChanged(&trainerTarget, Int(s.trainerTargetBpm))
        assignIfChanged(&groupTempo, s.groupTempo)
        assignIfChanged(&accentNoteLabel, s.accentNote.label)
        assignIfChanged(&restNoteLabel, s.restNote.label)
        assignIfChanged(&sessionPhase, s.sessionPhase.name)
        assignIfChanged(&tapHint, s.tapHint)
        assignIfChanged(&beatFlash, s.beatFlash)
        assignIfChanged(&beatAtMs, s.beatAtMs)
        assignIfChanged(&isAccentBeat, s.isAccentBeat)

        let nextAccents: [BeatAccentLevel] = s.beatAccents.map { accent in
            switch accent.name {
            case "STRONG": return .strong
            case "MUTE": return .mute
            default: return .normal
            }
        }
        if nextAccents != beatAccents { beatAccents = nextAccents }

        let nextSongs = s.songs.map { SongRow(id: $0.id, name: $0.name) }
        if nextSongs.map(\.id) != songs.map(\.id) || nextSongs.map(\.name) != songs.map(\.name) {
            songs = nextSongs
        }

        if !optionsLoaded {
            meterOptions = TimeSignature.companion.COMMON.map(\.label)
            let subs = Subdivision.values()
            subdivisionOptions = (0..<Int(subs.size)).compactMap {
                (subs.get(index: Int32($0)) as? Subdivision)?.label
            }
            let swings = SwingFeel.values()
            swingOptions = (0..<Int(swings.size)).compactMap {
                (swings.get(index: Int32($0)) as? SwingFeel)?.label
            }
            let notes = AccentNote.values()
            noteOptions = (0..<Int(notes.size)).compactMap {
                (notes.get(index: Int32($0)) as? AccentNote)?.label
            }
            muteOptions = MutePattern.companion.OPTIONS.map(\.label)
            optionsLoaded = true
        }
        let nextTones = s.toneOptions.map(\.label)
        if nextTones != toneOptions { toneOptions = nextTones }

        let ds = controller.detectState.value
        if let success = ds as? DetectStateSuccess {
            let opts = success.options.map { $0.intValue }
            if opts != listenOptions { listenOptions = opts }
            assignIfChanged(&listenStatus, "Pick a tempo")
        } else if ds is DetectStateListening {
            assignIfChanged(&listenStatus, "Listening…")
            if !listenOptions.isEmpty { listenOptions = [] }
        } else if ds is DetectStateAnalyzing {
            assignIfChanged(&listenStatus, "Analyzing…")
            if !listenOptions.isEmpty { listenOptions = [] }
        } else if let failed = ds as? DetectStateFailed {
            assignIfChanged(&listenStatus, failed.reason.name)
            if !listenOptions.isEmpty { listenOptions = [] }
        } else {
            if !isPlaying { assignIfChanged(&listenStatus, "") }
            if !listenOptions.isEmpty { listenOptions = [] }
        }
    }

    private func assignIfChanged<T: Equatable>(_ current: inout T, _ next: T) {
        if current != next { current = next }
    }

    private func setupAudioSessionObservers() {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            self?.handleInterruption(note)
        }

        routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            self?.handleRouteChange(note)
        }
    }

    private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let typeValue = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }
        switch type {
        case .began:
            if isPlaying || runner.isRunning(engine: engine) {
                interruptedWhilePlaying = true
                controller.stop()
                refreshFromState()
            }
        case .ended:
            let optionValue = info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionValue)
            if interruptedWhilePlaying && options.contains(.shouldResume) {
                interruptedWhilePlaying = false
                if sink.activatePlaybackSession() {
                    controller.start()
                    refreshFromState()
                }
            } else {
                interruptedWhilePlaying = false
            }
        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ note: Notification) {
        guard let info = note.userInfo,
              let reasonValue = info[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }
        if reason == .oldDeviceUnavailable {
            interruptedWhilePlaying = false
            controller.stop()
            refreshFromState()
        }
    }

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            self?.start(); return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.stop(); return .success
        }
        center.stopCommand.addTarget { [weak self] _ in
            self?.stop(); return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.togglePlay(); return .success
        }
    }

    private static func publishNowPlaying(playing: Bool, bpm: Int, subtitle: String) {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: "\(bpm) BPM",
            MPMediaItemPropertyArtist: "Metrom",
            MPMediaItemPropertyAlbumTitle: subtitle,
        ]
        MPNowPlayingInfoCenter.default().playbackState = playing ? .playing : .paused
    }
}
