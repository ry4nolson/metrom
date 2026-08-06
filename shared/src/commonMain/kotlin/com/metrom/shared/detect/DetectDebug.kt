package com.metrom.shared.detect

/**
 * One-shot analysis snapshot for the listen debug UI.
 * Survives DetectState reset until the next capture starts.
 */
data class DetectCandidate(
    val bpm: Int,
    val lag: Int,
    val rawPeak: Float,
    val score: Float,
    val isWinner: Boolean,
    /** If this BPM came from a one-shot octave promote, the pre-promote BPM. */
    val promotedFrom: Int? = null
)

data class DetectDebug(
    /** Downsampled waveform in roughly -1..1 for drawing. */
    val waveform: FloatArray,
    /** Onset strength envelope (post local-norm). */
    val onset: FloatArray,
    /** Autocorrelation values for lags 17..173 inclusive. */
    val acf: FloatArray,
    val candidates: List<DetectCandidate>,
    /** Best-effort BPM even when confidence rejected the result. */
    val bpm: Int?,
    val confidence: Float,
    val accepted: Boolean,
    val octaveDoubled: Boolean,
    /** Assumed beat times in seconds over the capture window. */
    val beatTimesSec: FloatArray,
    val durationSec: Float,
    val sampleRate: Int = 44100
) {
    companion object {
        const val ACF_MIN_LAG = 17
        const val ACF_MAX_LAG = 173
        const val WAVEFORM_POINTS = 512

        fun downsampleWaveform(pcm: FloatArray, points: Int = WAVEFORM_POINTS): FloatArray {
            if (pcm.isEmpty() || points <= 0) return FloatArray(0)
            if (pcm.size <= points) return pcm.copyOf()
            val out = FloatArray(points)
            val bucket = pcm.size.toFloat() / points
            for (i in 0 until points) {
                val start = (i * bucket).toInt()
                val end = (((i + 1) * bucket).toInt()).coerceAtMost(pcm.size)
                var peak = 0f
                for (j in start until end) {
                    val a = kotlin.math.abs(pcm[j])
                    if (a > peak) peak = a
                }
                // Keep sign of max-abs sample in the bucket for a more wave-like look.
                var signed = 0f
                for (j in start until end) {
                    if (kotlin.math.abs(pcm[j]) == peak) {
                        signed = pcm[j]
                        break
                    }
                }
                out[i] = signed
            }
            return out
        }

        /**
         * Place a beat grid at [bpm] by maximizing onset energy over phase.
         */
        fun beatTimesForBpm(onset: FloatArray, bpm: Int, durationSec: Float): FloatArray {
            if (onset.isEmpty() || bpm <= 0) return FloatArray(0)
            val periodFrames = OnsetEnvelope.ENVELOPE_RATE * 60f / bpm
            if (periodFrames < 2f) return FloatArray(0)

            val period = periodFrames.toInt().coerceAtLeast(2)
            var bestPhase = 0
            var bestSum = -Float.MAX_VALUE
            val phases = period.coerceAtMost(64)
            for (phase in 0 until phases) {
                var sum = 0f
                var i = phase
                while (i < onset.size) {
                    sum += onset[i]
                    i += period
                }
                if (sum > bestSum) {
                    bestSum = sum
                    bestPhase = phase
                }
            }

            val times = ArrayList<Float>()
            var frame = bestPhase.toFloat()
            while (frame < onset.size) {
                val t = frame / OnsetEnvelope.ENVELOPE_RATE
                if (t in 0f..durationSec) times.add(t)
                frame += periodFrames
            }
            return times.toFloatArray()
        }
    }
}
