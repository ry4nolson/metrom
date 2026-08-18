import SwiftUI
import MetromShared

struct ThemePickerRow: View {
    let current: ColorTheme
    let saved: [ColorTheme]
    let onSelect: (String) -> Void
    let onEditCustom: () -> Void
    let onDeleteSaved: (String) -> Void

    private var themes: [ColorTheme] {
        let customPreview = current.id == ColorTheme.companion.CUSTOM_ID
            ? current
            : ColorTheme.companion.EMBER.asCustom()
        let presetList: [ColorTheme] = ColorTheme.companion.PRESETS
        return presetList + saved + [customPreview]
    }

    var body: some View {
        let columns = Array(repeating: GridItem(.flexible(minimum: 0), spacing: 10), count: 3)
        LazyVGrid(columns: columns, spacing: 10) {
            ForEach(themes, id: \.id) { theme in
                ThemeCard(
                    theme: theme,
                    selected: current.id == theme.id,
                    showEdit: theme.id == ColorTheme.companion.CUSTOM_ID,
                    onClick: { onSelect(theme.id) },
                    onEdit: theme.id == ColorTheme.companion.CUSTOM_ID ? onEditCustom : nil,
                    onDelete: theme.isSaved() ? { onDeleteSaved(theme.id) } : nil
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ThemeCard: View {
    let theme: ColorTheme
    let selected: Bool
    var showEdit: Bool = false
    let onClick: () -> Void
    var onEdit: (() -> Void)? = nil
    var onDelete: (() -> Void)? = nil

    var body: some View {
        let palette = MetromPalette(theme: theme)
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 4) {
                Text(theme.label)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(palette.bone)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                Spacer(minLength: 0)
                if showEdit, let onEdit {
                    Button(action: onEdit) {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(palette.ash)
                    }
                    .buttonStyle(.plain)
                }
                if let onDelete {
                    Button(action: onDelete) {
                        Image(systemName: "xmark")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(palette.ash)
                    }
                    .buttonStyle(.plain)
                }
            }
            HStack(spacing: 5) {
                Circle()
                    .fill(palette.pulse)
                    .frame(width: 16, height: 16)
                ForEach(0..<3, id: \.self) { _ in
                    Circle()
                        .fill(palette.ember)
                        .frame(width: 11, height: 11)
                }
            }
            Text("allegro 140")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(palette.ash)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(palette.ink)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(selected ? palette.ember : palette.inkLine, lineWidth: 1)
        )
        .onTapGesture(perform: onClick)
    }
}

struct ThemeEditorView: View {
    @EnvironmentObject var bridge: MetronomeBridge
    @Environment(\.metromPalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @State private var showSave = false
    @State private var saveName = "My theme"

    private var slots: [ColorSlot] {
        ColorSlots.shared.ALL
    }

    var body: some View {
        let grouped = Dictionary(grouping: slots, by: \.group)
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 16) {
                ForEach(["ACCENT", "STAGE", "TYPE"], id: \.self) { group in
                    if let rows = grouped[group] {
                        Text(group)
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(palette.ash)
                        ForEach(rows, id: \.key) { slot in
                            SlotRow(slot: slot)
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
        .navigationTitle("Custom theme")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(palette.ink, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(bridge.colorTheme.isLight() ? .light : .dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") {
                    let label = bridge.colorTheme.label
                    saveName = label == "Custom" ? "My theme" : label
                    showSave = true
                }
                .foregroundStyle(palette.emberSoft)
            }
        }
        .alert("Save theme", isPresented: $showSave) {
            TextField("Name", text: $saveName)
            Button("Save") {
                bridge.saveNamedTheme(saveName)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Adds this look to LOOK so you can pick it again.")
        }
    }
}

private struct SlotRow: View {
    @EnvironmentObject var bridge: MetronomeBridge
    @Environment(\.metromPalette) private var palette
    let slot: ColorSlot

    var body: some View {
        let hex = bridge.colorTheme.hex(key: slot.key)
        HStack(spacing: 12) {
            ColorPicker(
                "",
                selection: Binding(
                    get: { Color(hex: hex) },
                    set: { bridge.updateThemeSlot(key: slot.key, hex: $0.hexString()) }
                ),
                supportsOpacity: false
            )
            .labelsHidden()
            .frame(width: 32, height: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(slot.label)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(palette.bone)
                Text("#\(hex)")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(palette.ash)
            }
            Spacer()
        }
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
