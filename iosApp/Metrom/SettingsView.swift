import SwiftUI
import MetromShared

struct SettingsView: View {
    @EnvironmentObject var bridge: MetronomeBridge
    @Environment(\.dismiss) private var dismiss
    @Environment(\.metromPalette) private var palette
    var openMeters: Bool = false
    @State private var editingTheme = false
    @State private var editingMeters = false

    var body: some View {
        NavigationStack {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 22) {
                    settingsSection("SOUND") {
                        labeledChips("CLICK", bridge.toneOptions, bridge.toneLabel, bridge.selectTone)
                        if bridge.supportsPitchAccent {
                            labeledChips("ONE", bridge.noteOptions, bridge.accentNoteLabel, bridge.selectAccentNote)
                            labeledChips("OTHERS", bridge.noteOptions, bridge.restNoteLabel, bridge.selectRestNote)
                        }
                    }

                    settingsSection("LEVEL") {
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
                            Text("\(Int((bridge.volume * 100).rounded()))%")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(palette.ash)
                                .frame(width: 40, alignment: .trailing)
                        }
                        toggleRow(
                            title: "Mute",
                            subtitle: "Silence the click without stopping",
                            icon: bridge.muted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                            on: bridge.muted,
                            action: bridge.toggleMute
                        )
                    }

                    settingsSection("FEEL") {
                        toggleRow(
                            title: "Haptics",
                            subtitle: "Vibrate on each click",
                            icon: "iphone.radiowaves.left.and.right",
                            on: bridge.hapticsOn,
                            action: bridge.toggleHaptics
                        )
                    }

                    settingsSection("METER") {
                        Text("Odd meters like 7/4 or 11/8")
                            .font(.system(size: 14))
                            .foregroundStyle(palette.ash)
                        if !bridge.customMeters.isEmpty {
                            FlowLayout {
                                ForEach(bridge.customMeters, id: \.label) { sig in
                                    ChoiceChip(
                                        label: sig.label,
                                        selected: sig.label == bridge.meterLabel
                                    ) {
                                        bridge.selectMeter(sig.label)
                                    }
                                }
                            }
                        }
                        ChoiceChip(label: "Custom meters", selected: false) {
                            editingMeters = true
                        }
                    }

                    settingsSection("LOOK") {
                        ThemePickerRow(
                            current: bridge.colorTheme,
                            saved: bridge.savedThemes,
                            onSelect: bridge.selectColorTheme,
                            onEditCustom: {
                                bridge.customizeCurrentTheme()
                                editingTheme = true
                            },
                            onDeleteSaved: bridge.deleteSavedTheme
                        )
                    }

                    settingsSection("ABOUT") {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Metrom")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(palette.bone)
                            Text("Version \(appVersion)")
                                .font(.system(size: 14))
                                .foregroundStyle(palette.ash)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(palette.inkElevated)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(palette.inkLine, lineWidth: 1)
                        )
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 12)
                .padding(.bottom, 32)
            }
            .background(
                LinearGradient(
                    colors: [palette.backgroundTop, palette.ink, palette.backgroundBottom],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
            )
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(palette.emberSoft)
                }
            }
            .toolbarBackground(palette.ink, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(bridge.colorTheme.isLight() ? .light : .dark, for: .navigationBar)
            .navigationDestination(isPresented: $editingTheme) {
                ThemeEditorView()
            }
            .navigationDestination(isPresented: $editingMeters) {
                CustomMetersView()
            }
            .onAppear {
                if openMeters { editingMeters = true }
            }
        }
        .preferredColorScheme(bridge.colorTheme.isLight() ? .light : .dark)
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }

    private func settingsSection<Content: View>(
        _ title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(palette.ash)
            content()
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func labeledChips(
        _ title: String,
        _ options: [String],
        _ selected: String,
        _ select: @escaping (String) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(palette.ash)
            FlowLayout {
                ForEach(options, id: \.self) { opt in
                    ChoiceChip(label: opt, selected: opt == selected) { select(opt) }
                }
            }
        }
    }

    private func toggleRow(
        title: String,
        subtitle: String,
        icon: String,
        on: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Toggle(isOn: Binding(
            get: { on },
            set: { _ in action() }
        )) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(on ? palette.ember : palette.ash)
                    .frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(palette.bone)
                    Text(subtitle)
                        .font(.system(size: 13))
                        .foregroundStyle(palette.ash)
                }
            }
        }
        .tint(palette.ember)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(palette.inkElevated)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(palette.inkLine, lineWidth: 1)
        )
    }
}

struct CustomMetersView: View {
    @EnvironmentObject var bridge: MetronomeBridge
    @Environment(\.metromPalette) private var palette
    @State private var beats = 7
    @State private var noteValue = 4

    private let noteValues = [1, 2, 4, 8, 16]

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 16) {
                Text("BEATS")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(palette.ash)
                HStack(spacing: 12) {
                    Button {
                        beats = max(1, beats - 1)
                    } label: {
                        Image(systemName: "minus")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(palette.mist)
                            .frame(width: 48, height: 48)
                            .background(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .fill(palette.inkElevated)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .stroke(palette.inkLine, lineWidth: 1)
                            )
                    }
                    .buttonStyle(.plain)
                    Text("\(beats)")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(palette.bone)
                        .frame(width: 48)
                    Button {
                        beats = min(16, beats + 1)
                    } label: {
                        Image(systemName: "plus")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(palette.mist)
                            .frame(width: 48, height: 48)
                            .background(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .fill(palette.inkElevated)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .stroke(palette.inkLine, lineWidth: 1)
                            )
                    }
                    .buttonStyle(.plain)
                }

                Text("NOTE")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(palette.ash)
                FlowLayout {
                    ForEach(noteValues, id: \.self) { note in
                        ChoiceChip(label: "\(note)", selected: noteValue == note) {
                            noteValue = note
                        }
                    }
                }

                ChoiceChip(label: "Add \(beats)/\(noteValue)", selected: false) {
                    bridge.addCustomMeter(beats: Int32(beats), noteValue: Int32(noteValue))
                }

                if !bridge.customMeters.isEmpty {
                    Text("SAVED")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(palette.ash)
                    FlowLayout {
                        ForEach(bridge.customMeters, id: \.label) { sig in
                            HStack(spacing: 4) {
                                ChoiceChip(
                                    label: sig.label,
                                    selected: sig.label == bridge.meterLabel
                                ) {
                                    bridge.selectMeter(sig.label)
                                }
                                Button {
                                    bridge.deleteCustomMeter(sig)
                                } label: {
                                    Image(systemName: "xmark")
                                        .font(.system(size: 11, weight: .semibold))
                                        .foregroundStyle(palette.ash)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .background(
            LinearGradient(
                colors: [palette.backgroundTop, palette.ink, palette.backgroundBottom],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
        )
        .navigationTitle("Custom meters")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(palette.ink, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(bridge.colorTheme.isLight() ? .light : .dark, for: .navigationBar)
        .onAppear {
            if let parsed = TimeSignature.companion.parse(label: bridge.meterLabel) {
                beats = Int(parsed.beats)
                noteValue = Int(parsed.noteValue)
            }
        }
    }
}
