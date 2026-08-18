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
    @Published private(set) var accentsCustomized: Bool = false
    @Published private(set) var countInBars: Int = 1
    @Published private(set) var muteLabel: String = "Off"
    @Published private(set) var trainerEnabled: Bool = false
    @Published private(set) var trainerTarget: Int = 120
    @Published private(set) var trainerStep: Int = 2
    @Published private(set) var trainerEveryBars: Int = 4
    @Published private(set) var trainerAutoStop: Bool = true
    @Published private(set) var trainerStartBpm: Int = 80
    @Published private(set) var sessionBar: Int = 0
    @Published private(set) var mutePlayBars: Int = 1
    @Published private(set) var muteSilentBars: Int = 0
    @Published private(set) var listenOptions: [Int] = []
    @Published private(set) var listenStatus: String = ""
    @Published private(set) var listenProgress: Float = 0
    @Published private(set) var listenPhase: ListenPhase = .idle
    @Published private(set) var listenDebug: ListenDebugSnapshot? = nil
    @Published private(set) var savedSections: [SavedSectionRow] = []
    @Published private(set) var activeSavedSectionId: String? = nil
    @Published private(set) var setlists: [SetlistRow] = []
    @Published private(set) var activeSetlistId: String? = nil
    @Published private(set) var activeSectionIndex: Int = -1
    @Published private(set) var sectionBar: Int = 0
    @Published private(set) var inSetMode: Bool = false
    @Published private(set) var groupTempo: Bool = false
    @Published private(set) var accentNoteLabel: String = "A4"
    @Published private(set) var restNoteLabel: String = "Off"
    @Published private(set) var supportsPitchAccent: Bool = true
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
    @Published private(set) var colorTheme: ColorTheme = ColorTheme.companion.EMBER
    @Published private(set) var palette: MetromPalette = .ember
    @Published private(set) var savedThemes: [ColorTheme] = []
    @Published private(set) var customMeters: [TimeSignature] = []

    enum BeatAccentLevel: String {
        case strong, normal, mute
    }

    enum ListenPhase: Equatable {
        case idle
        case listening
        case analyzing
        case success
        case failed
    }

    struct SavedSectionRow: Identifiable, Equatable {
        let id: String
        let name: String
        let detail: String
    }

    struct SetlistRow: Identifiable, Equatable {
        let id: String
        let name: String
        let sectionCount: Int
        let sections: [SectionRow]
    }

    struct SectionRow: Identifiable, Equatable {
        let id: String
        let summary: String
        let bars: Int
        let autoAdvance: Bool
    }

    /// Swift-side copy of shared DetectDebug for Canvas drawing.
    struct ListenDebugSnapshot: Equatable {
        struct Candidate: Identifiable, Equatable {
            let id: String
            let bpm: Int
            let lag: Int
            let rawPeak: Float
            let score: Float
            let isWinner: Bool
            let promotedFrom: Int?
        }

        let waveform: [Float]
        let onset: [Float]
        let acf: [Float]
        let beatTimesSec: [Float]
        let durationSec: Float
        let confidence: Float
        let accepted: Bool
        let octaveDoubled: Bool
        let bpm: Int?
        let candidates: [Candidate]
        let acfMinLag: Int
        let acfMaxLag: Int
        let envelopeRate: Float
    }

    private let controller: MetronomeController
    private let engine: MetronomeEngine
    private let sink: AVAudioPcmSink
    private let runner: IosEngineRunner
    private let themeStore: ColorThemeStore
    private let meterStore: CustomMeterStore
    private let metromDatabase: MetromDatabase
    private var pollTimer: Timer?
    private var optionsLoaded = false
    private var interruptedWhilePlaying = false
    private var interruptionObserver: NSObjectProtocol?
    private var routeChangeObserver: NSObjectProtocol?
    private var lastDebugStamp: String? = nil

    private var ui: MetronomeUiState? {
        controller.state.value as? MetronomeUiState
    }

    init() {
        let prefs = IosPrefsStore()
        themeStore = ColorThemeStore(prefs: prefs)
        meterStore = CustomMeterStore(prefs: prefs)
        metromDatabase = MetromSqlDriverKt.openMetromDatabase(driver: MetromSqlDriverKt.createMetromSqlDriver())
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
            database: metromDatabase,
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
        applyLoadedTheme()
        refreshCustomMeters()
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

    func selectColorTheme(_ id: String) {
        themeStore.select(id: id)
        applyLoadedTheme()
    }

    func customizeCurrentTheme() {
        themeStore.saveCustom(theme: colorTheme)
        applyLoadedTheme()
    }

    func updateThemeSlot(key: String, hex: String) {
        themeStore.updateSlot(key: key, hex: hex)
        applyLoadedTheme()
    }

    func saveNamedTheme(_ name: String) {
        themeStore.saveNamed(name: name, theme: colorTheme)
        applyLoadedTheme()
    }

    func deleteSavedTheme(_ id: String) {
        themeStore.deleteSaved(id: id)
        applyLoadedTheme()
    }

    private func applyLoadedTheme() {
        let loaded = themeStore.load()
        colorTheme = loaded
        palette = MetromPalette(theme: loaded)
        savedThemes = Self.colorThemes(themeStore.saved())
    }

    private static func colorThemes(_ list: Any) -> [ColorTheme] {
        if let arr = list as? [ColorTheme] { return arr }
        if let arr = list as? NSArray {
            return arr.compactMap { $0 as? ColorTheme }
        }
        return []
    }

    func selectMeter(_ label: String) {
        guard let ts = TimeSignature.companion.parse(label: label) else { return }
        controller.setTimeSignature(signature: ts)
        refreshFromState()
    }

    func addCustomMeter(beats: Int32, noteValue: Int32) {
        if let sig = meterStore.add(beats: beats, noteValue: noteValue) {
            refreshCustomMeters()
            controller.setTimeSignature(signature: sig)
            refreshFromState()
        }
    }

    func deleteCustomMeter(_ signature: TimeSignature) {
        meterStore.remove(signature: signature)
        refreshCustomMeters()
    }

    private func refreshCustomMeters() {
        customMeters = Self.timeSignatures(meterStore.all())
        meterOptions = TimeSignature.companion.COMMON.map(\.label) + customMeters.map(\.label)
    }

    private static func timeSignatures(_ list: Any) -> [TimeSignature] {
        if let arr = list as? [TimeSignature] { return arr }
        if let arr = list as? NSArray {
            return arr.compactMap { $0 as? TimeSignature }
        }
        return []
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

    func cycleTrainerStep() {
        let next = trainerStep >= 5 ? 1 : trainerStep + 1
        controller.setTrainerStep(step: Int32(next))
        refreshFromState()
    }

    func cycleTrainerEveryBars() {
        let next: Int32
        switch trainerEveryBars {
        case 2: next = 4
        case 4: next = 8
        default: next = 2
        }
        controller.setTrainerEveryBars(bars: next)
        refreshFromState()
    }

    func toggleTrainerAutoStop() {
        controller.toggleTrainerAutoStop()
        refreshFromState()
    }

    /// Prefill for the save-section dialog (matches Section.autoName).
    func suggestedSectionName() -> String {
        "\(bpm) · \(meterLabel) · \(subdivisionLabel)"
    }

    func saveSection(name: String? = nil) {
        controller.saveCurrentSection(name: name)
        refreshFromState()
    }

    func updateActiveSection() {
        controller.updateActiveSection()
        refreshFromState()
    }

    func renameSection(id: String, name: String) {
        if let section = ui?.savedSections.first(where: { $0.id == id }) {
            controller.renameSection(section: section, name: name)
        }
        refreshFromState()
    }

    func loadSection(id: String) {
        if let section = ui?.savedSections.first(where: { $0.id == id }) {
            controller.loadSection(section: section)
        }
        refreshFromState()
    }

    func deleteSection(id: String) {
        if let section = ui?.savedSections.first(where: { $0.id == id }) {
            _ = controller.deleteSection(section: section)
        }
        refreshFromState()
    }

    func createSetlist(name: String) {
        controller.createSetlist(name: name)
        refreshFromState()
    }

    func renameSetlist(id: String, name: String) {
        controller.renameSetlist(id: id, name: name)
        refreshFromState()
    }

    func deleteSetlist(id: String) {
        _ = controller.deleteSetlist(id: id)
        refreshFromState()
    }

    func addSectionFromCurrent(setlistId: String) {
        controller.addSectionFromCurrent(setlistId: setlistId)
        refreshFromState()
    }

    func removeSection(setlistId: String, sectionId: String) {
        controller.removeSection(setlistId: setlistId, sectionId: sectionId)
        refreshFromState()
    }

    func moveSection(setlistId: String, from: Int, to: Int) {
        controller.moveSection(setlistId: setlistId, from: Int32(from), to: Int32(to))
        refreshFromState()
    }

    func loadSetlist(id: String) {
        if let setlist = ui?.setlists.first(where: { $0.id == id }) {
            controller.loadSetlist(setlist: setlist)
        }
        refreshFromState()
    }

    func advanceSection() {
        controller.advanceSection()
        refreshFromState()
    }

    func exitSetlist() {
        controller.exitSetlist()
        refreshFromState()
    }

    func setSectionBars(setlistId: String, sectionId: String, bars: Int) {
        controller.setSectionBars(setlistId: setlistId, sectionId: sectionId, bars: Int32(max(0, bars)))
        refreshFromState()
    }

    func setSectionAutoAdvance(setlistId: String, sectionId: String, auto: Bool) {
        controller.setSectionAutoAdvance(setlistId: setlistId, sectionId: sectionId, autoAdvance: auto)
        refreshFromState()
    }

    func startListen() {
        if isPlaying { return }
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            beginListenCapture()
        case .denied:
            listenPhase = .failed
            listenStatus = "Mic access needed"
            listenOptions = []
            listenProgress = 0
        case .undetermined:
            AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if granted {
                        self.beginListenCapture()
                    } else {
                        self.listenPhase = .failed
                        self.listenStatus = "Mic access needed"
                        self.listenOptions = []
                        self.listenProgress = 0
                    }
                }
            }
        @unknown default:
            beginListenCapture()
        }
    }

    private func beginListenCapture() {
        listenStatus = "Listening…"
        listenPhase = .listening
        listenProgress = 0
        controller.listenCaptureRunner = { [weak self] mic in
            DispatchQueue.global(qos: .userInitiated).async {
                self?.controller.runListenCapture(mic: mic)
                DispatchQueue.main.async { self?.refreshFromState() }
            }
        }
        controller.startListen()
        refreshFromState()
    }

    func cancelListen() {
        controller.cancelListen()
        refreshFromState()
    }

    func applyListenBpm(_ bpm: Int32) {
        controller.applyListenBpm(bpm: bpm)
        refreshFromState()
    }

    func resetListen() { controller.resetListen(); refreshFromState() }

    func clearListenDebug() {
        controller.clearListenDebug()
        listenDebug = nil
        lastDebugStamp = nil
        refreshFromState()
    }

    func toggleGroupTempo() { controller.toggleGroupTempo(); refreshFromState() }

    func onAppBackground() {
        controller.onListenLifecyclePause()
        refreshFromState()
    }

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
        assignIfChanged(&trainerStep, Int(s.trainerStep))
        assignIfChanged(&trainerEveryBars, Int(s.trainerEveryBars))
        assignIfChanged(&trainerAutoStop, s.trainerAutoStop)
        assignIfChanged(&trainerStartBpm, Int(s.trainerStartBpm))
        assignIfChanged(&sessionBar, Int(s.sessionBar))
        assignIfChanged(&mutePlayBars, Int(s.mutePattern.playBars))
        assignIfChanged(&muteSilentBars, Int(s.mutePattern.silentBars))
        assignIfChanged(&groupTempo, s.groupTempo)
        assignIfChanged(&accentNoteLabel, s.accentNote.label)
        assignIfChanged(&restNoteLabel, s.restNote.label)
        assignIfChanged(&supportsPitchAccent, s.tone.supportsPitchAccent)
        assignIfChanged(&sessionPhase, s.sessionPhase.name)
        assignIfChanged(&tapHint, s.tapHint)
        assignIfChanged(&beatFlash, s.beatFlash)
        assignIfChanged(&beatAtMs, s.beatAtMs)
        assignIfChanged(&isAccentBeat, s.isAccentBeat)

        let nextActiveSavedSectionId = s.activeSavedSectionId
        if activeSavedSectionId != nextActiveSavedSectionId { activeSavedSectionId = nextActiveSavedSectionId }

        let nextActiveSetlistId = s.activeSetlistId
        if activeSetlistId != nextActiveSetlistId { activeSetlistId = nextActiveSetlistId }
        assignIfChanged(&activeSectionIndex, Int(s.activeSectionIndex))
        assignIfChanged(&sectionBar, Int(s.sectionBar))
        assignIfChanged(&inSetMode, s.inSetMode)

        let nextAccents: [BeatAccentLevel] = s.beatAccents.map { accent in
            switch accent.name {
            case "STRONG": return .strong
            case "MUTE": return .mute
            default: return .normal
            }
        }
        if nextAccents != beatAccents { beatAccents = nextAccents }
        assignIfChanged(&accentsCustomized, s.accentsCustomized)

        let nextSaved: [SavedSectionRow] = s.savedSections.map { section in
            var detail = "\(Int(section.bpm)) · \(section.timeSignature.label) · \(section.subdivision.label)"
            if section.swing.label != "Off" { detail += " · \(section.swing.label)" }
            if section.groupTempo { detail += " · dotted" }
            if Int(section.mutePattern.silentBars) > 0 { detail += " · mute \(section.mutePattern.label)" }
            if Int(section.countInBars) > 0 { detail += " · in \(Int(section.countInBars))" }
            return SavedSectionRow(id: section.id, name: section.displayName(), detail: detail)
        }
        if nextSaved != savedSections { savedSections = nextSaved }

        let nextSetlists: [SetlistRow] = s.setlists.map { setlist in
            let slots = s.setlistSlots(setlist: setlist)
            let sections: [SectionRow] = slots.map { slot in
                return SectionRow(
                    id: slot.section.id,
                    summary: slot.section.displayName(),
                    bars: Int(slot.section.bars),
                    autoAdvance: slot.autoAdvance
                )
            }
            return SetlistRow(
                id: setlist.id,
                name: setlist.name,
                sectionCount: sections.count,
                sections: sections
            )
        }
        if nextSetlists != setlists { setlists = nextSetlists }

        if !optionsLoaded {
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

        refreshListenState()
        refreshListenDebug()
    }

    private func refreshListenDebug() {
        guard let debug = controller.detectDebug.value as? DetectDebug else {
            if listenDebug != nil {
                listenDebug = nil
                lastDebugStamp = nil
            }
            return
        }
        let bpmVal = debug.bpm?.intValue
        let stamp = [
            String(debug.confidence),
            String(bpmVal ?? -1),
            String(debug.accepted),
            String(debug.octaveDoubled),
            String(debug.candidates.count),
            String(debug.waveform.size),
            String(debug.onset.size),
        ].joined(separator: "|")
        guard stamp != lastDebugStamp else { return }
        lastDebugStamp = stamp

        let candidates: [ListenDebugSnapshot.Candidate] = debug.candidates.enumerated().map { idx, c in
            ListenDebugSnapshot.Candidate(
                id: "\(c.bpm)-\(c.lag)-\(idx)",
                bpm: Int(c.bpm),
                lag: Int(c.lag),
                rawPeak: c.rawPeak,
                score: c.score,
                isWinner: c.isWinner,
                promotedFrom: c.promotedFrom.map { Int($0.intValue) }
            )
        }

        listenDebug = ListenDebugSnapshot(
            waveform: Self.copyFloats(debug.waveform),
            onset: Self.copyFloats(debug.onset),
            acf: Self.copyFloats(debug.acf),
            beatTimesSec: Self.copyFloats(debug.beatTimesSec),
            durationSec: debug.durationSec,
            confidence: debug.confidence,
            accepted: debug.accepted,
            octaveDoubled: debug.octaveDoubled,
            bpm: bpmVal.map { Int($0) },
            candidates: candidates,
            acfMinLag: Int(DetectDebug.companion.ACF_MIN_LAG),
            acfMaxLag: Int(DetectDebug.companion.ACF_MAX_LAG),
            envelopeRate: OnsetEnvelope.shared.ENVELOPE_RATE
        )
    }

    private static func copyFloats(_ arr: KotlinFloatArray) -> [Float] {
        let n = Int(arr.size)
        guard n > 0 else { return [] }
        var out = [Float](repeating: 0, count: n)
        for i in 0..<n {
            out[i] = arr.get(index: Int32(i))
        }
        return out
    }

    private func refreshListenState() {
        let ds = controller.detectState.value
        if let success = ds as? DetectStateSuccess {
            let opts = success.options.map { $0.intValue }
            if opts != listenOptions { listenOptions = opts }
            assignIfChanged(&listenStatus, "Pick a tempo")
            assignIfChanged(&listenPhase, .success)
            assignIfChanged(&listenProgress, 1)
        } else if let listening = ds as? DetectStateListening {
            assignIfChanged(&listenStatus, "Listening…")
            assignIfChanged(&listenPhase, .listening)
            let progress = listening.progress
            if abs(listenProgress - progress) > 0.01 { listenProgress = progress }
            if !listenOptions.isEmpty { listenOptions = [] }
        } else if ds is DetectStateAnalyzing {
            assignIfChanged(&listenStatus, "Finding the beat…")
            assignIfChanged(&listenPhase, .analyzing)
            assignIfChanged(&listenProgress, 1)
            if !listenOptions.isEmpty { listenOptions = [] }
        } else if let failed = ds as? DetectStateFailed {
            let reason = failed.reason.name
            let message: String
            switch reason {
            case "NO_CLEAR_BEAT": message = "Couldn't find a beat"
            case "MIC_UNAVAILABLE": message = "Mic unavailable"
            case "PERMISSION_DENIED": message = "Mic access needed"
            case "CANCELLED": message = ""
            default: message = reason
            }
            if reason == "CANCELLED" {
                assignIfChanged(&listenPhase, .idle)
                assignIfChanged(&listenStatus, "")
            } else {
                assignIfChanged(&listenPhase, .failed)
                assignIfChanged(&listenStatus, message)
            }
            assignIfChanged(&listenProgress, 0)
            if !listenOptions.isEmpty { listenOptions = [] }
        } else {
            assignIfChanged(&listenPhase, .idle)
            if !isPlaying { assignIfChanged(&listenStatus, "") }
            assignIfChanged(&listenProgress, 0)
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
