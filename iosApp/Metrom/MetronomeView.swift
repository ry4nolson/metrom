import SwiftUI

struct MetronomeView: View {
    @EnvironmentObject var bridge: MetronomeBridge
    @State private var practiceExpanded = false
    @State private var songsExpanded = false
    @State private var bpmScale: CGFloat = 1

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [MetromTheme.backgroundTop, MetromTheme.ink, MetromTheme.backgroundBottom],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            PendulumAtmosphere(
                isPlaying: bridge.isPlaying,
                beatFlash: bridge.beatFlash,
                isAccent: bridge.isAccentBeat,
                bpm: bridge.bpm,
                groupTempo: bridge.groupTempo,
                phase: bridge.sessionPhase
            )
            .allowsHitTesting(false)

            VStack(spacing: 0) {
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 0) {
                        header
                        beatRail
                            .padding(.top, 10)
                        Text("tap beats · strong / normal / mute")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(MetromTheme.ash)
                            .padding(.top, 6)
                        tempoHero
                            .padding(.top, 16)
                        Text(secondaryLabel)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(MetromTheme.phaseColor(bridge.sessionPhase))
                            .frame(height: 18)
                            .padding(.top, 4)
                        listenStrip
                            .padding(.top, 10)
                        chipRow(presets: [60, 72, 80, 92, 100, 120, 140, 160].map { "\($0)" }) { label in
                            if let v = Int32(label) { bridge.setBpm(v) }
                        } isSelected: { $0 == "\(bridge.bpm)" }
                        .padding(.top, 12)

                        controls
                            .padding(.top, 18)

                        expandable("PRACTICE", summary: practiceSummary, expanded: $practiceExpanded) {
                            practiceBody
                        }
                        .padding(.top, 14)

                        expandable("SONGS", summary: bridge.songs.isEmpty ? "bookmark to save" : "\(bridge.songs.count) saved", expanded: $songsExpanded) {
                            songsBody
                        }
                        .padding(.top, 12)

                        Spacer(minLength: 120)
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 8)
                }

                transportDock
            }
        }
        .onChange(of: bridge.beatFlash) { _ in
            guard bridge.isPlaying else { return }
            withAnimation(.spring(response: 0.18, dampingFraction: 0.45)) {
                bpmScale = bridge.isAccentBeat ? 1.06 : 1.03
            }
            withAnimation(.spring(response: 0.35, dampingFraction: 0.55)) {
                bpmScale = 1
            }
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("METROM")
                    .font(.system(size: 28, weight: .heavy, design: .rounded))
                    .foregroundStyle(MetromTheme.bone)
                Text(bridge.statusLine)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(MetromTheme.phaseColor(bridge.sessionPhase))
            }
            Spacer()
            iconButton("bookmark.fill", tint: MetromTheme.copper, action: bridge.saveSong)
            iconButton(
                bridge.muted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                tint: bridge.muted ? MetromTheme.ash : MetromTheme.bone,
                action: bridge.toggleMute
            )
            iconButton(
                "iphone.radiowaves.left.and.right",
                tint: bridge.hapticsOn ? MetromTheme.ember : MetromTheme.ash,
                action: bridge.toggleHaptics
            )
        }
    }

    private var beatRail: some View {
        HStack(alignment: .bottom, spacing: 8) {
            ForEach(Array(bridge.beatAccents.enumerated()), id: \.offset) { idx, level in
                let active = bridge.isPlaying && idx == bridge.activeBeat
                Button {
                    bridge.cycleBeatAccent(Int32(idx))
                } label: {
                    VStack(spacing: 6) {
                        Capsule()
                            .fill(beatFill(level: level, active: active))
                            .frame(maxWidth: .infinity)
                            .frame(height: beatHeight(level))
                            .overlay {
                                if level == .mute {
                                    Capsule().stroke(MetromTheme.ash.opacity(0.45), lineWidth: 1)
                                }
                            }
                        Text("\(idx + 1)")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(active ? MetromTheme.emberSoft : MetromTheme.ash)
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .simultaneousGesture(
                    LongPressGesture(minimumDuration: 0.45).onEnded { _ in
                        bridge.resetBeatAccents()
                    }
                )
            }
        }
        .frame(height: 48)
    }

    private var tempoHero: some View {
        HStack(spacing: 12) {
            RoundIconButton(systemName: "minus") { bridge.nudgeBpm(-1) }
            VStack(spacing: 4) {
                Text("\(bridge.bpm)")
                    .font(.system(size: 92, weight: .bold, design: .rounded))
                    .foregroundStyle(bridge.sessionPhase == "SILENT" ? MetromTheme.ash : MetromTheme.bone)
                    .scaleEffect(bpmScale)
                    .frame(height: 100)
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .gesture(
                        DragGesture()
                            .onChanged { value in
                                let steps = Int((-value.translation.height) / 14)
                                // Handled via discrete nudge on end would be better;
                                // keep simple: ignore continuous drag for now
                                _ = steps
                            }
                    )
                Text(bridge.tapHint ?? "BPM · nudge to change")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(bridge.tapHint == nil ? MetromTheme.ash : MetromTheme.emberSoft)
                    .frame(height: 18)
            }
            .frame(maxWidth: .infinity)
            RoundIconButton(systemName: "plus") { bridge.nudgeBpm(1) }
        }
    }

    private var listenStrip: some View {
        VStack(spacing: 8) {
            HStack(spacing: 10) {
                Image(systemName: "ear")
                    .foregroundStyle(bridge.isPlaying ? MetromTheme.ash : MetromTheme.copper)
                ChoiceChip(
                    label: bridge.isPlaying ? "Stop to listen" : "Listen",
                    selected: false,
                    action: { if !bridge.isPlaying { bridge.startListen() } }
                )
                .opacity(bridge.isPlaying ? 0.35 : 1)
                .disabled(bridge.isPlaying)
                if !bridge.listenStatus.isEmpty {
                    Text(bridge.listenStatus)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(MetromTheme.ash)
                }
                Spacer(minLength: 0)
                if !bridge.listenOptions.isEmpty {
                    Button("Dismiss") { bridge.resetListen() }
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(MetromTheme.ash)
                }
            }
            if !bridge.listenOptions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(bridge.listenOptions, id: \.self) { opt in
                            ChoiceChip(label: "\(opt)", selected: false) {
                                bridge.applyListenBpm(Int32(opt))
                            }
                        }
                    }
                }
            }
        }
        .frame(minHeight: 40)
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 14) {
            labeledChips("METER", bridge.meterOptions, bridge.meterLabel, bridge.selectMeter) {
                ChoiceChip(label: "Accents", selected: false, action: bridge.resetBeatAccents)
            }
            labeledChips("GRID", bridge.subdivisionOptions, bridge.subdivisionLabel, bridge.selectSubdivision)
            if bridge.subdivisionLabel == "×2" || bridge.subdivisionLabel == "×4" {
                labeledChips("SWING", bridge.swingOptions, bridge.swingLabel, bridge.selectSwing)
            }
            if bridge.meterLabel.hasSuffix("/8") {
                labeledChips(
                    "BPM MEANS",
                    ["Each pulse", "Dotted"],
                    bridge.groupTempo ? "Dotted" : "Each pulse"
                ) { label in
                    let wantDotted = label == "Dotted"
                    if wantDotted != bridge.groupTempo { bridge.toggleGroupTempo() }
                }
            }
            labeledChips("SOUND", bridge.toneOptions, bridge.toneLabel, bridge.selectTone)
            labeledChips("ONE", bridge.noteOptions, bridge.accentNoteLabel, bridge.selectAccentNote)
            labeledChips("OTHERS", bridge.noteOptions, bridge.restNoteLabel, bridge.selectRestNote)
        }
    }

    private var practiceBody: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("COUNT-IN")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(MetromTheme.ash)
            HStack(spacing: 8) {
                ForEach([0, 1, 2, 4], id: \.self) { bars in
                    ChoiceChip(
                        label: bars == 0 ? "Off" : "\(bars)",
                        selected: bridge.countInBars == bars
                    ) { bridge.setCountIn(Int32(bars)) }
                }
            }
            Text("MUTE BARS")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(MetromTheme.ash)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(bridge.muteOptions, id: \.self) { opt in
                        ChoiceChip(label: opt, selected: opt == bridge.muteLabel) {
                            bridge.selectMutePattern(opt)
                        }
                    }
                }
            }
            Toggle(isOn: Binding(
                get: { bridge.trainerEnabled },
                set: { _ in bridge.toggleTrainer() }
            )) {
                Text("Tempo trainer → \(bridge.trainerTarget)")
                    .foregroundStyle(MetromTheme.mist)
            }
            .tint(MetromTheme.ember)
            if bridge.trainerEnabled {
                ChoiceChip(label: "Cycle target", selected: false, action: bridge.cycleTrainerTarget)
            }
        }
    }

    private var songsBody: some View {
        VStack(alignment: .leading, spacing: 8) {
            if bridge.songs.isEmpty {
                Text("No saved songs")
                    .font(.system(size: 13))
                    .foregroundStyle(MetromTheme.ash)
            }
            ForEach(bridge.songs) { song in
                HStack {
                    Button(song.name) { bridge.loadSong(id: song.id) }
                        .foregroundStyle(MetromTheme.bone)
                    Spacer()
                    Button { bridge.deleteSong(id: song.id) } label: {
                        Image(systemName: "trash")
                            .foregroundStyle(MetromTheme.ash)
                    }
                }
            }
        }
    }

    private var transportDock: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: bridge.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .foregroundStyle(MetromTheme.ash)
                Slider(
                    value: Binding(
                        get: { Double(bridge.volume) },
                        set: { bridge.setVolume(Float($0)) }
                    ),
                    in: 0...1
                )
                .tint(MetromTheme.ember)
            }
            .padding(.horizontal, 24)

            HStack(spacing: 10) {
                TransportChip(label: "TAP", icon: "hand.tap.fill", action: bridge.tapTempo)
                    .frame(maxWidth: .infinity)
                TransportChip(label: "−5") { bridge.nudgeBpm(-5) }
                    .frame(width: 64)
                Button(action: bridge.togglePlay) {
                    Image(systemName: bridge.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(MetromTheme.ink)
                        .frame(width: 84, height: 84)
                        .background(
                            Circle().fill(
                                RadialGradient(
                                    colors: [MetromTheme.emberSoft, MetromTheme.ember, Color(red: 0.72, green: 0.20, blue: 0.07)],
                                    center: .center,
                                    startRadius: 4,
                                    endRadius: 48
                                )
                            )
                        )
                }
                .buttonStyle(.plain)
                TransportChip(label: "+5") { bridge.nudgeBpm(5) }
                    .frame(width: 64)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 20)
        }
        .padding(.top, 10)
        .background(
            LinearGradient(
                colors: [.clear, MetromTheme.ink.opacity(0.92), MetromTheme.ink],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .bottom)
        )
    }

    private var secondaryLabel: String {
        if bridge.trainerEnabled && bridge.isPlaying {
            return "TRAIN → \(bridge.trainerTarget)"
        }
        var parts = [MetromTheme.tempoMarking(bridge.bpm)]
        if bridge.groupTempo { parts.append("dotted") }
        if bridge.swingLabel != "Off" { parts.append("swing \(bridge.swingLabel.lowercased())") }
        return parts.joined(separator: " · ")
    }

    private var practiceSummary: String {
        var bits: [String] = []
        if bridge.countInBars > 0 { bits.append("count-in \(bridge.countInBars)") }
        if bridge.muteLabel != "Off" { bits.append("mute \(bridge.muteLabel)") }
        if bridge.trainerEnabled { bits.append("train → \(bridge.trainerTarget)") }
        return bits.isEmpty ? "count-in · mute · trainer" : bits.joined(separator: " · ")
    }

    private func labeledChips(
        _ title: String,
        _ options: [String],
        _ selected: String,
        _ select: @escaping (String) -> Void,
        @ViewBuilder trailing: () -> some View = { EmptyView() }
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(MetromTheme.ash)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(options, id: \.self) { opt in
                        ChoiceChip(label: opt, selected: opt == selected) { select(opt) }
                    }
                    trailing()
                }
            }
        }
    }

    private func chipRow(
        presets: [String],
        select: @escaping (String) -> Void,
        isSelected: @escaping (String) -> Bool
    ) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(presets, id: \.self) { p in
                    ChoiceChip(label: p, selected: isSelected(p)) { select(p) }
                }
            }
        }
    }

    private func expandable<Content: View>(
        _ title: String,
        summary: String,
        expanded: Binding<Bool>,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { expanded.wrappedValue.toggle() }
            } label: {
                HStack {
                    Text(title)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(MetromTheme.ash)
                    Spacer()
                    Text(summary)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(MetromTheme.mist)
                        .lineLimit(1)
                    Image(systemName: expanded.wrappedValue ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(MetromTheme.ash)
                }
            }
            .buttonStyle(.plain)
            if expanded.wrappedValue {
                content()
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(MetromTheme.inkElevated.opacity(0.7))
                    )
            }
        }
    }

    private func iconButton(_ systemName: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
        }
        .buttonStyle(.plain)
    }

    private func beatHeight(_ level: MetronomeBridge.BeatAccentLevel) -> CGFloat {
        switch level {
        case .strong: return 14
        case .normal: return 8
        case .mute: return 4
        }
    }

    private func beatFill(level: MetronomeBridge.BeatAccentLevel, active: Bool) -> Color {
        switch (level, active) {
        case (.strong, true): return MetromTheme.pulse
        case (.mute, true): return MetromTheme.ash.opacity(0.45)
        case (_, true): return MetromTheme.ember
        case (.strong, false): return MetromTheme.inkLine.opacity(0.95)
        case (.mute, false): return MetromTheme.inkLine.opacity(0.22)
        case (.normal, false): return MetromTheme.inkLine.opacity(0.5)
        }
    }
}

/// Pendulum that eases between apexes with cos(π·t) over each beat (matches Android Atmosphere).
private struct PendulumAtmosphere: View {
    let isPlaying: Bool
    let beatFlash: Int64
    let isAccent: Bool
    let bpm: Int
    let groupTempo: Bool
    let phase: String

    @State private var lastFlash: Int64 = 0
    @State private var apexSign: Double = 1
    @State private var epochMs: Double = 0

    private let ampDeg: Double = 38

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: false)) { timeline in
            let nowMs = timeline.date.timeIntervalSince1970 * 1000.0
            let angle = currentAngle(nowMs: nowMs)

            Canvas { context, size in
                let pivot = CGPoint(x: size.width / 2, y: size.height * 0.19)
                let arm: CGFloat = min(size.width, size.height) * 0.42
                let rad = angle * .pi / 180
                let bob = CGPoint(
                    x: pivot.x + arm * CGFloat(sin(rad)),
                    y: pivot.y + arm * CGFloat(cos(rad))
                )
                var path = Path()
                path.move(to: pivot)
                path.addLine(to: bob)
                let glow: Color = {
                    if phase == "SILENT" { return MetromTheme.ash }
                    return isAccent ? MetromTheme.pulse : MetromTheme.ember
                }()
                context.stroke(path, with: .color(MetromTheme.mist.opacity(0.35)), lineWidth: 2)
                context.fill(
                    Path(ellipseIn: CGRect(x: bob.x - 10, y: bob.y - 10, width: 20, height: 20)),
                    with: .color(glow.opacity(isPlaying ? 0.85 : 0.2))
                )
                context.fill(
                    Path(ellipseIn: CGRect(x: bob.x - 22, y: bob.y - 22, width: 44, height: 44)),
                    with: .color(glow.opacity(isPlaying ? 0.18 : 0.04))
                )
            }
            .onChange(of: beatFlash) { flash in
                guard isPlaying, flash != 0, flash != lastFlash else { return }
                lastFlash = flash
                apexSign *= -1
                // Wall clock — must match TimelineView's timeline.date (beatAtMs is monotonic).
                epochMs = Date().timeIntervalSince1970 * 1000.0
            }
            .onChange(of: isPlaying) { playing in
                if !playing {
                    lastFlash = 0
                    epochMs = 0
                    apexSign = 1
                }
            }
        }
        .opacity(0.9)
    }

    private func currentAngle(nowMs: Double) -> Double {
        guard isPlaying, epochMs > 0 else { return 0 }
        let period = beatPeriodMs
        let age = nowMs - epochMs
        // Before hear-time, hold previous apex; after one period, rest at new apex.
        let p = min(max(age / period, 0), 1)
        return apexSign * ampDeg * cos(.pi * p)
    }

    private var beatPeriodMs: Double {
        var ms = 60_000.0 / Double(max(30, min(300, bpm)))
        if groupTempo { ms /= 3 }
        return ms
    }
}
