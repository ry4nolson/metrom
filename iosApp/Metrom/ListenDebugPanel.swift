import SwiftUI

/// Mirrors Android ListenDebugPanel — waveform / onset / ACF + candidates.
struct ListenDebugPanel: View {
    let debug: MetronomeBridge.ListenDebugSnapshot
    let onApplyBpm: (Int) -> Void
    let onClear: () -> Void

    @Environment(\.metromPalette) private var palette
    @State private var expanded = true

    private var summary: String {
        var s = debug.accepted ? "options" : "rejected"
        s += String(format: " · conf %.2f", debug.confidence)
        if let bpm = debug.bpm { s += " · overlay \(bpm)" }
        if debug.octaveDoubled { s += " · ×2" }
        return s
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
            } label: {
                HStack {
                    Text("LISTEN DEBUG")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(palette.ash)
                    Spacer()
                    Text(summary)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.mist)
                        .lineLimit(1)
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(palette.ash)
                }
            }
            .buttonStyle(.plain)

            if expanded {
                VStack(alignment: .leading, spacing: 12) {
                    chartLabel("waveform + assumed beats")
                    DebugWaveformChart(
                        samples: debug.waveform,
                        beatTimesSec: debug.beatTimesSec,
                        durationSec: debug.durationSec
                    )
                    .frame(height: 72)

                    chartLabel("onset envelope + beats")
                    DebugOnsetChart(
                        onset: debug.onset,
                        beatTimesSec: debug.beatTimesSec,
                        durationSec: debug.durationSec
                    )
                    .frame(height: 64)

                    chartLabel("autocorrelation (30–300 BPM)")
                    DebugAcfChart(
                        acf: debug.acf,
                        winnerBpm: debug.bpm,
                        acfMinLag: debug.acfMinLag,
                        acfMaxLag: debug.acfMaxLag,
                        envelopeRate: debug.envelopeRate
                    )
                    .frame(height: 64)

                    Text(confidenceLine)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(debug.accepted ? palette.emberSoft : palette.ash)

                    if !debug.candidates.isEmpty {
                        chartLabel("candidates (tap to try)")
                        ForEach(debug.candidates) { c in
                            Button {
                                onApplyBpm(c.bpm)
                            } label: {
                                HStack {
                                    Text(candidateLabel(c))
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(c.isWinner ? palette.emberSoft : palette.mist)
                                        .lineLimit(1)
                                    Spacer()
                                    Text("use")
                                        .font(.system(size: 12, weight: .medium))
                                        .foregroundStyle(palette.copper)
                                }
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .fill(c.isWinner ? palette.ember.opacity(0.14) : palette.inkElevated)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .stroke(
                                            c.isWinner ? palette.ember.opacity(0.55) : palette.inkLine,
                                            lineWidth: 1
                                        )
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    if let bpm = debug.bpm, debug.octaveDoubled {
                        Text("final after ×2: \(bpm)")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(palette.copper)
                        ChoiceChip(label: "Use \(bpm)", selected: true) {
                            onApplyBpm(bpm)
                        }
                    }

                    ChoiceChip(label: "Clear debug", selected: false, action: onClear)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(palette.inkElevated.opacity(0.7))
                )
            }
        }
    }

    private var confidenceLine: String {
        var s = String(format: "conf %.3f", debug.confidence)
        s += debug.accepted ? " ≥ 0.30 → accept" : " < 0.30 → reject"
        if debug.octaveDoubled { s += " · octave doubled" }
        return s
    }

    private func candidateLabel(_ c: MetronomeBridge.ListenDebugSnapshot.Candidate) -> String {
        var s = "\(c.bpm)"
        if c.isWinner { s += " ★" }
        if let from = c.promotedFrom { s += " ←\(from)" }
        s += String(format: "  raw %.3f  score %.3f", c.rawPeak, c.score)
        return s
    }

    private func chartLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(palette.ash)
    }
}

// MARK: - Charts

private struct DebugWaveformChart: View {
    @Environment(\.metromPalette) private var palette
    let samples: [Float]
    let beatTimesSec: [Float]
    let durationSec: Float

    var body: some View {
        Canvas { context, size in
            drawChartBackground(context: context, size: size, color: palette.ink)
            guard samples.count > 1 else { return }
            let midY = size.height / 2
            let maxAbs = max(samples.map { abs($0) }.max() ?? 1e-6, 1e-6)
            var path = Path()
            let n = samples.count
            for i in 0..<n {
                let x = CGFloat(i) / CGFloat(max(n - 1, 1)) * size.width
                let y = midY - CGFloat(samples[i] / maxAbs) * midY * 0.9
                if i == 0 { path.move(to: CGPoint(x: x, y: y)) }
                else { path.addLine(to: CGPoint(x: x, y: y)) }
            }
            context.stroke(path, with: .color(palette.mist), lineWidth: 1.5)
            drawBeatMarkers(
                context: context,
                size: size,
                beatTimesSec: beatTimesSec,
                durationSec: durationSec,
                color: palette.emberSoft.opacity(0.85)
            )
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .background(palette.ink)
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(palette.inkLine, lineWidth: 1)
        )
    }
}

private struct DebugOnsetChart: View {
    @Environment(\.metromPalette) private var palette
    let onset: [Float]
    let beatTimesSec: [Float]
    let durationSec: Float

    var body: some View {
        Canvas { context, size in
            drawChartBackground(context: context, size: size, color: palette.ink)
            guard onset.count > 1 else { return }
            let maxV = max(onset.max() ?? 1e-6, 1e-6)
            var path = Path()
            let n = onset.count
            for i in 0..<n {
                let x = CGFloat(i) / CGFloat(max(n - 1, 1)) * size.width
                let y = size.height - CGFloat(onset[i] / maxV) * size.height * 0.92
                if i == 0 { path.move(to: CGPoint(x: x, y: y)) }
                else { path.addLine(to: CGPoint(x: x, y: y)) }
            }
            context.stroke(path, with: .color(palette.copper), lineWidth: 1.5)
            drawBeatMarkers(
                context: context,
                size: size,
                beatTimesSec: beatTimesSec,
                durationSec: durationSec,
                color: palette.ember.opacity(0.9)
            )
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .background(palette.ink)
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(palette.inkLine, lineWidth: 1)
        )
    }
}

private struct DebugAcfChart: View {
    @Environment(\.metromPalette) private var palette
    let acf: [Float]
    let winnerBpm: Int?
    let acfMinLag: Int
    let acfMaxLag: Int
    let envelopeRate: Float

    var body: some View {
        Canvas { context, size in
            drawChartBackground(context: context, size: size, color: palette.ink)
            let lo = acfMinLag
            let hi = acfMaxLag
            guard acf.count > hi, hi > lo else { return }
            var minV = Float.greatestFiniteMagnitude
            var maxV = -Float.greatestFiniteMagnitude
            for lag in lo...hi {
                let v = acf[lag]
                if v < minV { minV = v }
                if v > maxV { maxV = v }
            }
            let span = max(maxV - minV, 1e-6)
            var path = Path()
            let count = hi - lo
            for lag in lo...hi {
                let x = CGFloat(lag - lo) / CGFloat(count) * size.width
                let y = size.height - CGFloat((acf[lag] - minV) / span) * size.height * 0.92
                if lag == lo { path.move(to: CGPoint(x: x, y: y)) }
                else { path.addLine(to: CGPoint(x: x, y: y)) }
            }
            context.stroke(path, with: .color(palette.mist), lineWidth: 1.5)

            if let winnerBpm, winnerBpm > 0, envelopeRate > 0 {
                let lag = Int((60 * envelopeRate / Float(winnerBpm)).rounded())
                    .clamped(to: lo...hi)
                let x = CGFloat(lag - lo) / CGFloat(count) * size.width
                var line = Path()
                line.move(to: CGPoint(x: x, y: 0))
                line.addLine(to: CGPoint(x: x, y: size.height))
                context.stroke(line, with: .color(palette.ember), lineWidth: 2)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .background(palette.ink)
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(palette.inkLine, lineWidth: 1)
        )
    }
}

private func drawChartBackground(context: GraphicsContext, size: CGSize, color: Color) {
    context.fill(
        Path(CGRect(origin: .zero, size: size)),
        with: .color(color)
    )
}

private func drawBeatMarkers(
    context: GraphicsContext,
    size: CGSize,
    beatTimesSec: [Float],
    durationSec: Float,
    color: Color
) {
    guard durationSec > 0, !beatTimesSec.isEmpty else { return }
    for t in beatTimesSec {
        let x = CGFloat(min(max(t / durationSec, 0), 1)) * size.width
        var line = Path()
        line.move(to: CGPoint(x: x, y: 0))
        line.addLine(to: CGPoint(x: x, y: size.height))
        context.stroke(line, with: .color(color), lineWidth: 1.2)
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
