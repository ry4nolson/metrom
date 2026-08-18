import SwiftUI
import MetromShared
import UIKit

struct MetromPalette: Equatable {
    var ink: Color
    var inkElevated: Color
    var inkLine: Color
    var ash: Color
    var mist: Color
    var bone: Color
    var ember: Color
    var emberSoft: Color
    var emberDeep: Color
    var copper: Color
    var pulse: Color
    var backgroundTop: Color
    var backgroundBottom: Color

    init(theme: ColorTheme) {
        ink = Color(hex: theme.ink)
        inkElevated = Color(hex: theme.inkElevated)
        inkLine = Color(hex: theme.inkLine)
        ash = Color(hex: theme.ash)
        mist = Color(hex: theme.mist)
        bone = Color(hex: theme.bone)
        ember = Color(hex: theme.ember)
        emberSoft = Color(hex: theme.emberSoft)
        emberDeep = Color(hex: theme.emberDeep)
        copper = Color(hex: theme.copper)
        pulse = Color(hex: theme.pulse)
        backgroundTop = Color(hex: theme.backgroundTop)
        backgroundBottom = Color(hex: theme.backgroundBottom)
    }

    static let ember = MetromPalette(theme: ColorTheme.companion.EMBER)

    func phaseColor(_ phase: String) -> Color {
        switch phase {
        case "COUNT_IN": return copper
        case "PLAYING": return emberSoft
        case "SILENT": return mist
        case "TRAINER_DONE": return pulse
        default: return ash
        }
    }
}

private struct MetromPaletteKey: EnvironmentKey {
    static let defaultValue = MetromPalette.ember
}

extension EnvironmentValues {
    var metromPalette: MetromPalette {
        get { self[MetromPaletteKey.self] }
        set { self[MetromPaletteKey.self] = newValue }
    }
}

enum MetromTheme {
    static func tempoMarking(_ bpm: Int) -> String {
        switch bpm {
        case ..<60: return "LARGO"
        case 60..<76: return "ADAGIO"
        case 76..<108: return "ANDANTE"
        case 108..<120: return "MODERATO"
        case 120..<168: return "ALLEGRO"
        case 168..<200: return "PRESTO"
        default: return "PRESTISSIMO"
        }
    }
}

extension Color {
    init(hex: String) {
        let cleaned = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: cleaned).scanHexInt64(&int)
        self.init(
            red: Double((int >> 16) & 0xFF) / 255,
            green: Double((int >> 8) & 0xFF) / 255,
            blue: Double(int & 0xFF) / 255
        )
    }

    func hexString() -> String {
        let ui = UIColor(self)
        var r: CGFloat = 0
        var g: CGFloat = 0
        var b: CGFloat = 0
        var a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "%02X%02X%02X", Int(r * 255), Int(g * 255), Int(b * 255))
    }
}

struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        let result = layout(in: maxWidth, subviews: subviews)
        return CGSize(width: proposal.width ?? result.size.width, height: result.size.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = layout(in: bounds.width, subviews: subviews)
        for index in subviews.indices {
            let origin = result.origins[index]
            subviews[index].place(
                at: CGPoint(x: bounds.minX + origin.x, y: bounds.minY + origin.y),
                proposal: ProposedViewSize(result.sizes[index])
            )
        }
    }

    private func layout(in maxWidth: CGFloat, subviews: Subviews) -> (origins: [CGPoint], sizes: [CGSize], size: CGSize) {
        var origins: [CGPoint] = []
        var sizes: [CGSize] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var usedWidth: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            origins.append(CGPoint(x: x, y: y))
            sizes.append(size)
            rowHeight = max(rowHeight, size.height)
            x += size.width + spacing
            usedWidth = max(usedWidth, x - spacing)
        }

        return (origins, sizes, CGSize(width: usedWidth, height: y + rowHeight))
    }
}

struct ChoiceChip: View {
    @Environment(\.metromPalette) private var palette
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(selected ? palette.emberSoft : palette.mist)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(selected ? palette.ember.opacity(0.18) : palette.inkElevated)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(
                            selected ? palette.ember.opacity(0.7) : palette.inkLine,
                            lineWidth: 1
                        )
                )
        }
        .buttonStyle(.plain)
    }
}

struct TransportChip: View {
    @Environment(\.metromPalette) private var palette
    let label: String
    var icon: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon {
                    Image(systemName: icon)
                        .foregroundStyle(palette.copper)
                }
                Text(label)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(palette.bone)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 64)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(palette.inkElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(palette.inkLine, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

struct RoundIconButton: View {
    @Environment(\.metromPalette) private var palette
    let systemName: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(palette.mist)
                .frame(width: 48, height: 48)
                .background(Circle().fill(palette.inkElevated))
                .overlay(Circle().stroke(palette.inkLine, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
