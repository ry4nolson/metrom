import SwiftUI

enum MetromTheme {
    static let ink = Color(red: 0.039, green: 0.039, blue: 0.047)
    static let inkElevated = Color(red: 0.078, green: 0.078, blue: 0.094)
    static let inkLine = Color(red: 0.165, green: 0.165, blue: 0.196)
    static let ash = Color(red: 0.545, green: 0.545, blue: 0.588)
    static let mist = Color(red: 0.784, green: 0.784, blue: 0.816)
    static let bone = Color(red: 0.949, green: 0.941, blue: 0.918)
    static let ember = Color(red: 1.0, green: 0.416, blue: 0.239)
    static let emberSoft = Color(red: 1.0, green: 0.561, blue: 0.400)
    static let copper = Color(red: 0.831, green: 0.647, blue: 0.455)
    static let pulse = Color(red: 1.0, green: 0.784, blue: 0.341)
    static let backgroundTop = Color(red: 0.071, green: 0.071, blue: 0.094)
    static let backgroundBottom = Color(red: 0.031, green: 0.031, blue: 0.039)

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

    static func phaseColor(_ phase: String) -> Color {
        switch phase {
        case "COUNT_IN": return copper
        case "PLAYING": return emberSoft
        case "SILENT": return mist
        case "TRAINER_DONE": return pulse
        default: return ash
        }
    }
}

struct ChoiceChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(selected ? MetromTheme.emberSoft : MetromTheme.mist)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(selected ? MetromTheme.ember.opacity(0.18) : MetromTheme.ink.opacity(0.35))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(
                            selected ? MetromTheme.ember.opacity(0.7) : MetromTheme.inkLine,
                            lineWidth: 1
                        )
                )
        }
        .buttonStyle(.plain)
    }
}

struct TransportChip: View {
    let label: String
    var icon: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon {
                    Image(systemName: icon)
                        .foregroundStyle(MetromTheme.copper)
                }
                Text(label)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(MetromTheme.bone)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 64)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(MetromTheme.inkElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(MetromTheme.inkLine, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

struct RoundIconButton: View {
    let systemName: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(MetromTheme.mist)
                .frame(width: 48, height: 48)
                .background(Circle().fill(MetromTheme.inkElevated))
                .overlay(Circle().stroke(MetromTheme.inkLine, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
