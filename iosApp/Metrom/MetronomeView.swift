import SwiftUI

struct MetronomeView: View {
    @EnvironmentObject var bridge: MetronomeBridge
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.metromPalette) private var palette

    @State private var practiceExpanded = false
    @State private var songsExpanded = false
    @State private var setlistsExpanded = false
    @State private var editingSetlistId: String?
    @State private var bpmScale: CGFloat = 1
    @State private var bpmDragAccum: CGFloat = 0
    @State private var bpmDragLastY: CGFloat = 0
    @State private var showSaveDialog = false
    @State private var showNewSetlistDialog = false
    @State private var showSettings = false
    @State private var openMeters = false
    @State private var saveName = ""
    @State private var newSetlistName = ""
    @State private var renamingSong: MetronomeBridge.SongRow?
    @State private var renamingSetlist: MetronomeBridge.SetlistRow?
    @State private var renameText = ""

    private var isWide: Bool {
        horizontalSizeClass == .regular
    }

    var body: some View {
        GeometryReader { geo in
            let isLandscape = geo.size.width > geo.size.height
            let pad = horizontalPadding(landscape: isLandscape)

            ZStack {
                LinearGradient(
                    colors: [palette.backgroundTop, palette.ink, palette.backgroundBottom],
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

                if isLandscape {
                    landscapeLayout(padding: pad)
                } else {
                    portraitLayout(padding: pad)
                }
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
        .onChange(of: scenePhase) { phase in
            if phase == .background || phase == .inactive {
                bridge.onAppBackground()
            }
        }
        .onChange(of: bridge.inSetMode) { active in
            if active { setlistsExpanded = true }
        }
        .alert("Save song", isPresented: $showSaveDialog) {
            TextField("Name", text: $saveName)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                bridge.saveSong(name: saveName)
            }
        } message: {
            Text("Bookmark tempo, meter, swing, and practice settings.")
        }
        .alert(
            "Rename song",
            isPresented: Binding(
                get: { renamingSong != nil },
                set: { if !$0 { renamingSong = nil } }
            )
        ) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) { renamingSong = nil }
            Button("Save") {
                if let song = renamingSong {
                    bridge.renameSong(id: song.id, name: renameText)
                }
                renamingSong = nil
            }
        }
        .alert("New setlist", isPresented: $showNewSetlistDialog) {
            TextField("Name", text: $newSetlistName)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                bridge.createSetlist(name: newSetlistName)
            }
        } message: {
            Text("Save an ordered set of songs.")
        }
        .alert(
            "Rename setlist",
            isPresented: Binding(
                get: { renamingSetlist != nil },
                set: { if !$0 { renamingSetlist = nil } }
            )
        ) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) { renamingSetlist = nil }
            Button("Save") {
                if let setlist = renamingSetlist {
                    bridge.renameSetlist(id: setlist.id, name: renameText)
                }
                renamingSetlist = nil
            }
        }
        .sheet(isPresented: $showSettings, onDismiss: { openMeters = false }) {
            SettingsView(openMeters: openMeters)
                .environmentObject(bridge)
                .environment(\.metromPalette, bridge.palette)
        }
    }

    // MARK: - Metrics

    private func horizontalPadding(landscape: Bool) -> CGFloat {
        if landscape { return isWide ? 32 : 20 }
        return isWide ? 36 : 24
    }

    private func sectionGap(landscape: Bool) -> CGFloat {
        if landscape { return isWide ? 12 : 8 }
        return isWide ? 18 : 12
    }

    private func bpmFontSize(landscape: Bool) -> CGFloat {
        if landscape { return isWide ? 96 : 64 }
        return isWide ? 120 : 92
    }

    private func playButtonSize(landscape: Bool) -> CGFloat {
        if landscape { return isWide ? 84 : 64 }
        return isWide ? 96 : 84
    }

    private func titleSize(landscape: Bool) -> CGFloat {
        if landscape { return isWide ? 28 : 22 }
        return isWide ? 34 : 28
    }

    // MARK: - Portrait (single column, full width)

    private func portraitLayout(padding: CGFloat) -> some View {
        let gap = sectionGap(landscape: false)
        return VStack(spacing: 0) {
            header(landscape: false)
                .padding(.horizontal, padding)
                .padding(.top, isWide ? 16 : 8)

            ScrollView(showsIndicators: false) {
                VStack(spacing: 0) {
                    beatRail(landscape: false)
                        .padding(.top, gap)
                    Text("tap beats · strong / normal / mute")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.ash)
                        .padding(.top, isWide ? 10 : 6)
                    tempoHero(landscape: false)
                        .padding(.top, isWide ? 28 : 16)
                    phaseBanner
                        .padding(.top, isWide ? 8 : 4)
                    Text(secondaryLabel)
                        .font(.system(size: isWide ? 14 : 12, weight: .semibold))
                        .foregroundStyle(palette.phaseColor(bridge.sessionPhase))
                        .frame(height: 18)
                    practiceStrip
                        .padding(.top, gap)
                    setlistStrip
                    listenStrip
                        .padding(.top, gap)
                    if let debug = bridge.listenDebug {
                        ListenDebugPanel(
                            debug: debug,
                            onApplyBpm: { bridge.applyListenBpm(Int32($0)) },
                            onClear: bridge.clearListenDebug
                        )
                        .padding(.top, 10)
                    }
                    bpmPresets
                        .padding(.top, gap)
                    controls(landscape: false)
                        .padding(.top, isWide ? 28 : 18)
                    expandable("PRACTICE", summary: practiceSummary, expanded: $practiceExpanded) {
                        practiceBody
                    }
                    .padding(.top, isWide ? 22 : 14)
                    expandable("SONGS", summary: bridge.songs.isEmpty ? "bookmark to save" : "\(bridge.songs.count) saved", expanded: $songsExpanded) {
                        songsBody
                    }
                    .padding(.top, gap)
                    expandable("SETLISTS", summary: setlistSummary, expanded: $setlistsExpanded) {
                        setlistsBody
                    }
                    .padding(.top, gap)
                    Spacer(minLength: isWide ? 24 : 16)
                }
                .padding(.horizontal, padding)
                .frame(maxWidth: .infinity)
            }

            transportDock(landscape: false, padding: padding)
        }
    }

    // MARK: - Landscape (two columns, full-width transport)

    private func landscapeLayout(padding: CGFloat) -> some View {
        let gap = sectionGap(landscape: true)
        return VStack(spacing: 0) {
            HStack(alignment: .top, spacing: isWide ? 28 : 16) {
                VStack(spacing: gap) {
                    header(landscape: true)
                    beatRail(landscape: true)
                    Spacer(minLength: 0)
                    VStack(spacing: 4) {
                        tempoHero(landscape: true)
                        phaseBanner
                        Text(secondaryLabel)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(palette.phaseColor(bridge.sessionPhase))
                            .frame(height: 18)
                        practiceStrip
                        setlistStrip
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: gap) {
                        listenStrip
                        if let debug = bridge.listenDebug {
                            ListenDebugPanel(
                                debug: debug,
                                onApplyBpm: { bridge.applyListenBpm(Int32($0)) },
                                onClear: bridge.clearListenDebug
                            )
                        }
                        bpmPresets
                        controls(landscape: true)
                        expandable("PRACTICE", summary: practiceSummary, expanded: $practiceExpanded) {
                            practiceBody
                        }
                        expandable("SONGS", summary: bridge.songs.isEmpty ? "bookmark to save" : "\(bridge.songs.count) saved", expanded: $songsExpanded) {
                            songsBody
                        }
                        expandable("SETLISTS", summary: setlistSummary, expanded: $setlistsExpanded) {
                            setlistsBody
                        }
                        Spacer(minLength: 8)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .padding(.horizontal, padding)
            .padding(.top, isWide ? 16 : 10)

            transportDock(landscape: true, padding: padding)
        }
    }

    // MARK: - Shared sections

    private func header(landscape: Bool) -> some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("METROM")
                    .font(.system(size: titleSize(landscape: landscape), weight: .heavy, design: .rounded))
                    .foregroundStyle(palette.bone)
                Text(bridge.statusLine)
                    .font(.system(size: isWide && !landscape ? 13 : 12, weight: .semibold))
                    .foregroundStyle(palette.phaseColor(bridge.sessionPhase))
            }
            Spacer()
            iconButton("bookmark.fill", tint: palette.copper) {
                saveName = bridge.suggestedSongName()
                showSaveDialog = true
            }
            iconButton(
                bridge.muted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                tint: bridge.muted ? palette.ash : palette.bone,
                action: bridge.toggleMute
            )
            iconButton("gearshape.fill", tint: palette.bone) {
                showSettings = true
            }
        }
    }

    private func beatRail(landscape: Bool) -> some View {
        HStack(alignment: .bottom, spacing: isWide ? 12 : 8) {
            ForEach(Array(bridge.beatAccents.enumerated()), id: \.offset) { idx, level in
                let active = bridge.isPlaying && idx == bridge.activeBeat
                Button {
                    bridge.cycleBeatAccent(Int32(idx))
                } label: {
                    VStack(spacing: 6) {
                        Capsule()
                            .fill(beatFill(level: level, active: active))
                            .frame(maxWidth: .infinity)
                            .frame(height: beatHeight(level, landscape: landscape))
                            .overlay {
                                if level == .mute {
                                    Capsule().stroke(palette.ash.opacity(0.45), lineWidth: 1)
                                }
                            }
                        Text("\(idx + 1)")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(active ? palette.emberSoft : palette.ash)
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
        .frame(height: landscape ? (isWide ? 48 : 40) : (isWide ? 56 : 48))
    }

    private func tempoHero(landscape: Bool) -> some View {
        let size = bpmFontSize(landscape: landscape)
        return HStack(spacing: isWide && !landscape ? 20 : 12) {
            RoundIconButton(systemName: "minus") { bridge.nudgeBpm(-1) }
            VStack(spacing: 4) {
                Text("\(bridge.bpm)")
                    .font(.system(size: size, weight: .bold, design: .rounded))
                    .foregroundStyle(bridge.sessionPhase == "SILENT" ? palette.ash : palette.bone)
                    .scaleEffect(bpmScale)
                    .frame(height: size + 8)
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .gesture(
                        DragGesture(minimumDistance: 4)
                            .onChanged { value in
                                let dy = value.translation.height - bpmDragLastY
                                bpmDragLastY = value.translation.height
                                bpmDragAccum -= dy
                                while bpmDragAccum >= 14 {
                                    bridge.nudgeBpm(1)
                                    bpmDragAccum -= 14
                                }
                                while bpmDragAccum <= -14 {
                                    bridge.nudgeBpm(-1)
                                    bpmDragAccum += 14
                                }
                            }
                            .onEnded { _ in
                                bpmDragAccum = 0
                                bpmDragLastY = 0
                            }
                    )
                Text(bridge.tapHint ?? "BPM · drag to change")
                    .font(.system(size: isWide && !landscape ? 13 : 12, weight: .medium))
                    .foregroundStyle(bridge.tapHint == nil ? palette.ash : palette.emberSoft)
                    .frame(height: 18)
            }
            .frame(maxWidth: .infinity)
            RoundIconButton(systemName: "plus") { bridge.nudgeBpm(1) }
        }
    }

    @ViewBuilder
    private var phaseBanner: some View {
        let copy = phaseBannerCopy
        VStack(spacing: 2) {
            if let title = copy.title {
                Text(title)
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(palette.phaseColor(bridge.sessionPhase))
                    .lineLimit(1)
                if let detail = copy.detail {
                    Text(detail)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.ash)
                        .lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 52)
    }

    private var phaseBannerCopy: (title: String?, detail: String?) {
        guard bridge.isPlaying else { return (nil, nil) }
        switch bridge.sessionPhase {
        case "COUNT_IN":
            let remaining = max(1, bridge.countInBars - bridge.sessionBar)
            return (
                "\(remaining)",
                remaining == 1 ? "LAST BAR · COUNT IN" : "BARS LEFT · COUNT IN"
            )
        case "SILENT":
            return ("YOUR MOVE", "keep the pulse")
        case "TRAINER_DONE":
            return ("LOCKED", "target \(bridge.trainerTarget)")
        case "PLAYING":
            if let hint = bridge.tapHint, hint.hasPrefix("TRAIN →") {
                return (hint, "tempo step")
            }
            return (nil, nil)
        default:
            return (nil, nil)
        }
    }

    @ViewBuilder
    private var practiceStrip: some View {
        let showMute = !bridge.inSetMode && bridge.muteSilentBars > 0
        let showTrainer = !bridge.inSetMode && bridge.trainerEnabled
        if showMute || showTrainer {
            VStack(alignment: .leading, spacing: 8) {
                if showMute {
                    let play = bridge.mutePlayBars
                    let silent = bridge.muteSilentBars
                    let cycle = play + silent
                    let practiceBar: Int = {
                        if bridge.isPlaying && bridge.sessionBar >= bridge.countInBars {
                            return (bridge.sessionBar - bridge.countInBars) % cycle
                        }
                        return -1
                    }()
                    HStack(spacing: 6) {
                        ForEach(0..<cycle, id: \.self) { index in
                            let isSilentSlot = index >= play
                            let isCurrent = index == practiceBar
                            Capsule()
                                .fill(
                                    isCurrent && isSilentSlot ? palette.mist
                                        : isCurrent ? palette.ember
                                        : isSilentSlot ? palette.inkLine.opacity(0.35)
                                        : palette.ember.opacity(0.35)
                                )
                                .frame(height: 8)
                        }
                    }
                    Text(
                        bridge.sessionPhase == "SILENT"
                            ? "MUTE CYCLE · YOUR BARS"
                            : "MUTE CYCLE · \(bridge.muteLabel)"
                    )
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(bridge.sessionPhase == "SILENT" ? palette.mist : palette.ash)
                }

                if showTrainer {
                    let start = min(bridge.trainerStartBpm, bridge.trainerTarget)
                    let span = max(1, bridge.trainerTarget - start)
                    let progress = min(1, max(0, Float(bridge.bpm - start) / Float(span)))
                    let practice = max(0, bridge.sessionBar - bridge.countInBars)
                    let every = max(1, bridge.trainerEveryBars)
                    let until = (bridge.isPlaying && bridge.sessionBar >= bridge.countInBars)
                        ? every - (practice % every)
                        : every

                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(palette.inkLine)
                            Capsule()
                                .fill(palette.ember)
                                .frame(width: geo.size.width * CGFloat(progress))
                        }
                    }
                    .frame(height: 6)
                    Text("TRAIN \(start)→\(bridge.trainerTarget) · +\(bridge.trainerStep) in \(until)")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.emberSoft)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 4)
        }
    }

    private var listenStrip: some View {
        VStack(spacing: 8) {
            switch bridge.listenPhase {
            case .idle:
                HStack(spacing: 10) {
                    Image(systemName: "ear")
                        .foregroundStyle(bridge.isPlaying ? palette.ash : palette.copper)
                    ChoiceChip(
                        label: bridge.isPlaying ? "Stop to listen" : "Listen",
                        selected: false,
                        action: { if !bridge.isPlaying { bridge.startListen() } }
                    )
                    .opacity(bridge.isPlaying ? 0.35 : 1)
                    .disabled(bridge.isPlaying)
                    if bridge.isPlaying {
                        Text("stop to listen")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(palette.ash)
                    }
                    Spacer(minLength: 0)
                }

            case .listening:
                HStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .stroke(palette.inkLine, lineWidth: 3)
                        Circle()
                            .trim(from: 0, to: CGFloat(bridge.listenProgress))
                            .stroke(palette.ember, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                        Text("\(Int((bridge.listenProgress * 8).rounded()))s")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(palette.mist)
                    }
                    .frame(width: 36, height: 36)
                    Text("listening…")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(palette.emberSoft)
                    ChoiceChip(label: "Cancel", selected: false, action: bridge.cancelListen)
                    Spacer(minLength: 0)
                }

            case .analyzing:
                Text("finding the beat…")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(palette.copper)
                    .frame(maxWidth: .infinity, alignment: .leading)

            case .success:
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("pick a tempo")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(palette.bone)
                        Spacer()
                        Button("Dismiss") { bridge.resetListen() }
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(palette.ash)
                    }
                    FlowLayout {
                        ForEach(bridge.listenOptions, id: \.self) { opt in
                            ChoiceChip(label: "\(opt)", selected: false) {
                                bridge.applyListenBpm(Int32(opt))
                            }
                        }
                    }
                }

            case .failed:
                HStack(spacing: 10) {
                    if !bridge.listenStatus.isEmpty {
                        Text(bridge.listenStatus)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(palette.ash)
                    }
                    ChoiceChip(label: "Dismiss", selected: false, action: bridge.resetListen)
                    Spacer(minLength: 0)
                }
            }
        }
        .frame(minHeight: 40)
    }

    private var bpmPresets: some View {
        chipRow(presets: [60, 72, 80, 92, 100, 120, 140, 160].map { "\($0)" }) { label in
            if let v = Int32(label) { bridge.setBpm(v) }
        } isSelected: { $0 == "\(bridge.bpm)" }
    }

    private func controls(landscape: Bool) -> some View {
        VStack(alignment: .leading, spacing: landscape ? 12 : (isWide ? 18 : 14)) {
            labeledChips("METER", bridge.meterOptions, bridge.meterLabel, bridge.selectMeter) {
                ChoiceChip(label: "Custom", selected: false) {
                    openMeters = true
                    showSettings = true
                }
                if bridge.accentsCustomized {
                    ChoiceChip(label: "Reset accents", selected: false, action: bridge.resetBeatAccents)
                }
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
        }
    }

    private var practiceBody: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("COUNT IN")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(palette.ash)
            FlowLayout {
                ForEach([0, 1, 2, 4], id: \.self) { bars in
                    ChoiceChip(
                        label: bars == 0 ? "Off" : "\(bars) bar",
                        selected: bridge.countInBars == bars
                    ) { bridge.setCountIn(Int32(bars)) }
                }
            }

            let setLocked = bridge.inSetMode
            Group {
                Text(setLocked ? "MUTE BARS · SET MODE" : "MUTE BARS")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(palette.ash)
                FlowLayout {
                    ForEach(bridge.muteOptions, id: \.self) { opt in
                        ChoiceChip(label: opt, selected: opt == bridge.muteLabel) {
                            if !setLocked { bridge.selectMutePattern(opt) }
                        }
                    }
                }

                Text(setLocked ? "TRAINER · SET MODE" : "TRAINER")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(palette.ash)
                FlowLayout {
                    ChoiceChip(
                        label: bridge.trainerEnabled ? "On" : "Off",
                        selected: bridge.trainerEnabled
                    ) { if !setLocked { bridge.toggleTrainer() } }
                    if bridge.trainerEnabled {
                        ChoiceChip(
                            label: "→\(bridge.trainerTarget)",
                            selected: false
                        ) { if !setLocked { bridge.cycleTrainerTarget() } }
                        ChoiceChip(
                            label: "±\(bridge.trainerStep)",
                            selected: false
                        ) { if !setLocked { bridge.cycleTrainerStep() } }
                        ChoiceChip(
                            label: "each \(bridge.trainerEveryBars)",
                            selected: false
                        ) { if !setLocked { bridge.cycleTrainerEveryBars() } }
                        ChoiceChip(
                            label: bridge.trainerAutoStop ? "stop" : "hold",
                            selected: bridge.trainerAutoStop
                        ) { if !setLocked { bridge.toggleTrainerAutoStop() } }
                    }
                }
            }
            .opacity(setLocked ? 0.38 : 1)
        }
    }

    private var songsBody: some View {
        VStack(alignment: .leading, spacing: 10) {
            if bridge.activeSongId != nil {
                ChoiceChip(label: "Update active", selected: false, action: bridge.updateActiveSong)
            }
            if bridge.songs.isEmpty {
                Text("Bookmark tempo, meter, swing, and practice settings. Long-press to rename.")
                    .font(.system(size: 13))
                    .foregroundStyle(palette.ash)
            } else {
                ForEach(bridge.songs) { song in
                    let selected = bridge.activeSongId == song.id
                    HStack(alignment: .center, spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(song.name)
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(palette.bone)
                                .lineLimit(1)
                            Text(song.detail)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(palette.ash)
                                .lineLimit(2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                        .onTapGesture { bridge.loadSong(id: song.id) }
                        .onLongPressGesture {
                            renamingSong = song
                            renameText = song.name
                        }
                        Button {
                            bridge.deleteSong(id: song.id)
                        } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(palette.ash)
                                .frame(width: 32, height: 32)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(selected ? palette.ember.opacity(0.14) : Color.clear)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(
                                selected ? palette.ember.opacity(0.55) : palette.inkLine,
                                lineWidth: 1
                            )
                    )
                }
            }
        }
    }

    @ViewBuilder
    private var setlistStrip: some View {
        if bridge.inSetMode, let setlist = bridge.setlists.first(where: { $0.id == bridge.activeSetlistId }) {
            let section = setlist.sections.indices.contains(bridge.activeSectionIndex)
                ? setlist.sections[bridge.activeSectionIndex]
                : nil
            let count = setlist.sectionCount
            let indexLabel = "\(bridge.activeSectionIndex + 1)/\(count)"
            let sectionTitle = section?.summary ?? setlist.name
            let bars = section?.bars ?? 0
            let progress = bars > 0
                ? min(1, max(0, Float(bridge.sectionBar) / Float(bars)))
                : 0

            VStack(alignment: .leading, spacing: 8) {
                Text(setlist.name.uppercased())
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(palette.emberSoft)
                    .lineLimit(1)
                Text("section \(indexLabel) · \(sectionTitle)")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(palette.ash)
                    .lineLimit(1)
                if bars > 0 {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(palette.inkLine)
                            Capsule()
                                .fill(palette.ember)
                                .frame(width: geo.size.width * CGFloat(progress))
                        }
                    }
                    .frame(height: 6)
                    Text("\(min(max(0, bridge.sectionBar), bars)) / \(bars)")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.ash)
                }
                HStack(spacing: 8) {
                    ChoiceChip(label: "Next", selected: false, action: bridge.advanceSection)
                    ChoiceChip(label: "Exit", selected: false, action: bridge.exitSetlist)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 4)
        }
    }

    private var setlistsBody: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let editing = bridge.setlists.first(where: { $0.id == editingSetlistId }) {
                ChoiceChip(label: "All setlists", selected: false) {
                    editingSetlistId = nil
                }
                Text(editing.name)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(palette.bone)
                    .lineLimit(1)
                ChoiceChip(label: "Add current as section", selected: false) {
                    bridge.addSectionFromCurrent(setlistId: editing.id)
                }
                if editing.sections.isEmpty {
                    Text("Add the current tempo, meter, and tone as a section. Open-ended until you set a bar count.")
                        .font(.system(size: 13))
                        .foregroundStyle(palette.ash)
                } else {
                    ForEach(Array(editing.sections.enumerated()), id: \.element.id) { index, section in
                        sectionEditorRow(
                            setlistId: editing.id,
                            section: section,
                            selected: bridge.inSetMode
                                && bridge.activeSetlistId == editing.id
                                && bridge.activeSectionIndex == index,
                            isFirst: index == 0,
                            isLast: index == editing.sections.count - 1,
                            index: index
                        )
                    }
                }
            } else {
                ChoiceChip(label: "New setlist", selected: false) {
                    newSetlistName = "Set \(bridge.setlists.count + 1)"
                    showNewSetlistDialog = true
                }
                if bridge.setlists.isEmpty {
                    Text("Save an ordered set of songs. Tap to load, long-press to rename.")
                        .font(.system(size: 13))
                        .foregroundStyle(palette.ash)
                } else {
                    ForEach(bridge.setlists) { setlist in
                        setlistRow(setlist)
                    }
                }
            }
        }
    }

    private func setlistRow(_ setlist: MetronomeBridge.SetlistRow) -> some View {
        let selected = bridge.activeSetlistId == setlist.id
        let countLabel = setlist.sectionCount == 1 ? "1 section" : "\(setlist.sectionCount) sections"
        return HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(setlist.name)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(palette.bone)
                    .lineLimit(1)
                Text(countLabel)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(palette.ash)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture {
                bridge.loadSetlist(id: setlist.id)
                editingSetlistId = setlist.id
                setlistsExpanded = true
            }
            .onLongPressGesture {
                renamingSetlist = setlist
                renameText = setlist.name
            }
            Button {
                bridge.deleteSetlist(id: setlist.id)
                if editingSetlistId == setlist.id { editingSetlistId = nil }
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(palette.ash)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(selected ? palette.ember.opacity(0.14) : Color.clear)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(
                    selected ? palette.ember.opacity(0.55) : palette.inkLine,
                    lineWidth: 1
                )
        )
    }

    private func sectionEditorRow(
        setlistId: String,
        section: MetronomeBridge.SectionRow,
        selected: Bool,
        isFirst: Bool,
        isLast: Bool,
        index: Int
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 4) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(section.summary)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(palette.bone)
                        .lineLimit(1)
                    Text(sectionLengthLabel(section))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.ash)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    if !isFirst { bridge.moveSection(setlistId: setlistId, from: index, to: index - 1) }
                } label: {
                    Image(systemName: "chevron.up")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(isFirst ? palette.inkLine : palette.ash)
                        .frame(width: 28, height: 32)
                }
                .buttonStyle(.plain)
                .disabled(isFirst)
                Button {
                    if !isLast { bridge.moveSection(setlistId: setlistId, from: index, to: index + 1) }
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(isLast ? palette.inkLine : palette.ash)
                        .frame(width: 28, height: 32)
                }
                .buttonStyle(.plain)
                .disabled(isLast)
                Button {
                    bridge.removeSection(setlistId: setlistId, sectionId: section.id)
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(palette.ash)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
            }
            Text("BARS")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(palette.ash)
            FlowLayout {
                ForEach([0, 2, 4, 8, 16, 32], id: \.self) { bars in
                    ChoiceChip(
                        label: bars == 0 ? "Open" : "\(bars)",
                        selected: section.bars == bars
                    ) {
                        bridge.setSectionBars(setlistId: setlistId, sectionId: section.id, bars: bars)
                    }
                }
            }
            if section.bars > 0 {
                ChoiceChip(
                    label: "Auto",
                    selected: section.autoAdvance
                ) {
                    bridge.setSectionAutoAdvance(
                        setlistId: setlistId,
                        sectionId: section.id,
                        auto: !section.autoAdvance
                    )
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(selected ? palette.ember.opacity(0.14) : Color.clear)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(
                    selected ? palette.ember.opacity(0.55) : palette.inkLine,
                    lineWidth: 1
                )
        )
    }

    private func sectionLengthLabel(_ section: MetronomeBridge.SectionRow) -> String {
        if section.bars <= 0 { return "open-ended" }
        if section.autoAdvance { return "\(section.bars) bars · auto" }
        return "\(section.bars) bars"
    }

    private func transportDock(landscape: Bool, padding: CGFloat) -> some View {
        let playSize = playButtonSize(landscape: landscape)
        return Group {
            if landscape {
                HStack(alignment: .center, spacing: isWide ? 24 : 16) {
                    HStack(spacing: 12) {
                        Image(systemName: bridge.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                            .foregroundStyle(palette.ash)
                        Slider(
                            value: Binding(
                                get: { Double(bridge.volume) },
                                set: { bridge.setVolume(Float($0)) }
                            ),
                            in: 0...1
                        )
                        .tint(palette.ember)
                    }
                    .frame(maxWidth: .infinity)

                    HStack(spacing: 10) {
                        TransportChip(label: "TAP", icon: "hand.tap.fill", action: bridge.tapTempo)
                            .frame(maxWidth: .infinity)
                        TransportChip(label: "−5") { bridge.nudgeBpm(-5) }
                            .frame(width: 56)
                        playButton(size: playSize, iconSize: 22)
                        TransportChip(label: "+5") { bridge.nudgeBpm(5) }
                            .frame(width: 56)
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding(.horizontal, padding)
                .padding(.top, 10)
                .padding(.bottom, 12)
                .background(dockBackground)
            } else {
                VStack(spacing: isWide ? 14 : 12) {
                    HStack(spacing: 12) {
                        Image(systemName: bridge.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                            .foregroundStyle(palette.ash)
                        Slider(
                            value: Binding(
                                get: { Double(bridge.volume) },
                                set: { bridge.setVolume(Float($0)) }
                            ),
                            in: 0...1
                        )
                        .tint(palette.ember)
                    }
                    .padding(.horizontal, padding)

                    HStack(spacing: isWide ? 14 : 10) {
                        TransportChip(label: "TAP", icon: "hand.tap.fill", action: bridge.tapTempo)
                            .frame(maxWidth: .infinity)
                        TransportChip(label: "−5") { bridge.nudgeBpm(-5) }
                            .frame(width: isWide ? 72 : 64)
                        playButton(size: playSize, iconSize: isWide ? 32 : 28)
                        TransportChip(label: "+5") { bridge.nudgeBpm(5) }
                            .frame(width: isWide ? 72 : 64)
                    }
                    .padding(.horizontal, padding)
                    .padding(.bottom, 20)
                }
                .padding(.top, isWide ? 14 : 10)
                .background(dockBackground)
            }
        }
    }

    private var dockBackground: some View {
        LinearGradient(
            colors: [.clear, palette.ink.opacity(0.92), palette.ink],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea(edges: .bottom)
    }

    private func playButton(size: CGFloat, iconSize: CGFloat) -> some View {
        Button(action: bridge.togglePlay) {
            Image(systemName: bridge.isPlaying ? "pause.fill" : "play.fill")
                .font(.system(size: iconSize, weight: .bold))
                .foregroundStyle(palette.ink)
                .frame(width: size, height: size)
                .background(
                    Circle().fill(
                        RadialGradient(
                            colors: [palette.emberSoft, palette.ember, palette.emberDeep],
                            center: .center,
                            startRadius: 4,
                            endRadius: size * 0.57
                        )
                    )
                )
        }
        .buttonStyle(.plain)
    }

    private var secondaryLabel: String {
        if bridge.inSetMode {
            var parts = [MetromTheme.tempoMarking(bridge.bpm)]
            if bridge.groupTempo { parts.append("dotted") }
            if bridge.swingLabel != "Off" { parts.append("swing \(bridge.swingLabel.lowercased())") }
            return parts.joined(separator: " · ")
        }
        if bridge.trainerEnabled && bridge.isPlaying {
            let sign = bridge.trainerTarget < bridge.bpm ? "−" : "+"
            return "TRAIN \(bridge.trainerStartBpm)→\(bridge.trainerTarget) · \(sign)\(bridge.trainerStep)/\(bridge.trainerEveryBars)"
        }
        var parts = [MetromTheme.tempoMarking(bridge.bpm)]
        if bridge.groupTempo { parts.append("dotted") }
        if bridge.swingLabel != "Off" { parts.append("swing \(bridge.swingLabel.lowercased())") }
        return parts.joined(separator: " · ")
    }

    private var practiceSummary: String {
        if bridge.inSetMode {
            if let set = bridge.setlists.first(where: { $0.id == bridge.activeSetlistId }) {
                return "set · \(set.name)"
            }
            return "set mode"
        }
        var bits: [String] = []
        bits.append(bridge.countInBars == 0 ? "no count-in" : "count-in \(bridge.countInBars)")
        bits.append(bridge.muteSilentBars == 0 ? "mute off" : "mute \(bridge.muteLabel)")
        if bridge.trainerEnabled { bits.append("train →\(bridge.trainerTarget)") }
        return bits.joined(separator: " · ")
    }

    private var setlistSummary: String {
        if bridge.inSetMode, let set = bridge.setlists.first(where: { $0.id == bridge.activeSetlistId }) {
            let index = bridge.activeSectionIndex + 1
            return "\(set.name) · \(index)/\(set.sectionCount)"
        }
        return bridge.setlists.isEmpty ? "build a set" : "\(bridge.setlists.count) saved"
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
                .foregroundStyle(palette.ash)
            FlowLayout {
                ForEach(options, id: \.self) { opt in
                    ChoiceChip(label: opt, selected: opt == selected) { select(opt) }
                }
                trailing()
            }
        }
    }

    private func chipRow(
        presets: [String],
        select: @escaping (String) -> Void,
        isSelected: @escaping (String) -> Bool
    ) -> some View {
        FlowLayout {
            ForEach(presets, id: \.self) { p in
                ChoiceChip(label: p, selected: isSelected(p)) { select(p) }
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
                        .foregroundStyle(palette.ash)
                    Spacer()
                    Text(summary)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.mist)
                        .lineLimit(1)
                    Image(systemName: expanded.wrappedValue ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(palette.ash)
                }
            }
            .buttonStyle(.plain)
            if expanded.wrappedValue {
                content()
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(palette.inkElevated.opacity(0.7))
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

    private func beatHeight(_ level: MetronomeBridge.BeatAccentLevel, landscape: Bool) -> CGFloat {
        let wide = isWide && !landscape
        switch level {
        case .strong: return wide ? 16 : 14
        case .normal: return wide ? 10 : 8
        case .mute: return wide ? 5 : 4
        }
    }

    private func beatFill(level: MetronomeBridge.BeatAccentLevel, active: Bool) -> Color {
        switch (level, active) {
        case (.strong, true): return palette.pulse
        case (.mute, true): return palette.ash.opacity(0.45)
        case (_, true): return palette.ember
        case (.strong, false): return palette.inkLine.opacity(0.95)
        case (.mute, false): return palette.inkLine.opacity(0.22)
        case (.normal, false): return palette.inkLine.opacity(0.5)
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

    @Environment(\.metromPalette) private var palette
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
                    if phase == "SILENT" { return palette.ash }
                    return isAccent ? palette.pulse : palette.ember
                }()
                context.stroke(path, with: .color(palette.mist.opacity(0.35)), lineWidth: 2)
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
