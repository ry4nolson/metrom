package com.metrom.shared.detect

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Autocorrelation tempo estimate. Surfaces multiple musically-related candidates
 * (half-time / double-time) and lets the UI offer them — does not force one pick.
 */
object TempoEstimator {
    private const val MIN_BPM = 30
    private const val MAX_BPM = 300
    private const val MIN_LAG = DetectDebug.ACF_MIN_LAG
    private const val MAX_LAG = DetectDebug.ACF_MAX_LAG
    private const val TOP_PEAKS = 8
    private const val MAX_OPTIONS = 6
    private const val PRIOR_CENTER_BPM = 120f
    private const val PRIOR_SIGMA_OCT = 1.25f
    private const val OCTAVE_SUPPORT_RATIO = 0.7f
    private const val COMPETITIVE_RAW_RATIO = 0.92f
    private const val LAG_NEAR = 2
    private const val CONFIDENCE_FLOOR = 0.3f
    private const val EPS = 1e-8f

    data class EstimateDebug(
        val autocorrelation: FloatArray,
        /** Non-null when [confidence] clears the floor — options ready for the user. */
        val options: List<Int>,
        val candidates: List<DetectCandidate>,
        /** Suggested BPM for debug beat overlay only. */
        val bpm: Int?,
        val confidence: Float,
        val octaveDoubled: Boolean
    ) {
        val accepted: Boolean get() = options.isNotEmpty()
    }

    private data class Resolved(
        val lag: Int,
        val raw: Float,
        val bpm: Int,
        val promotedFrom: Int?
    )

    fun estimateWithDebug(envelope: FloatArray): EstimateDebug {
        val empty = EstimateDebug(
            autocorrelation = FloatArray(MAX_LAG + 1),
            options = emptyList(),
            candidates = emptyList(),
            bpm = null,
            confidence = 0f,
            octaveDoubled = false
        )
        val ac = autocorrelation(envelope) ?: return empty

        val peaks = localMaxima(ac)
        if (peaks.isEmpty()) {
            return empty.copy(autocorrelation = ac)
        }

        val top = peaks
            .sortedByDescending { it.value }
            .take(TOP_PEAKS)

        // Keep BOTH the raw peak BPM and a one-shot 2× promote when supported.
        // Half-time and double-time are both valid feels — offer both.
        val byBpm = LinkedHashMap<Int, Resolved>()
        fun put(r: Resolved) {
            val existing = byBpm[r.bpm]
            if (existing == null || r.raw > existing.raw) byBpm[r.bpm] = r
        }

        for (p in top) {
            val baseBpm = lagToBpm(p.lag).roundToInt().coerceIn(MIN_BPM, MAX_BPM)
            put(Resolved(p.lag, p.value, baseBpm, promotedFrom = null))

            val doubled = baseBpm * 2
            if (doubled in MIN_BPM..MAX_BPM) {
                val targetLag = bpmToLag(doubled.toFloat())
                val supportLag = nearestLocalMaxLag(ac, targetLag)
                if (supportLag != null) {
                    val support = ac[supportLag]
                    if (support >= p.value * OCTAVE_SUPPORT_RATIO) {
                        val bpm = lagToBpm(supportLag).roundToInt().coerceIn(MIN_BPM, MAX_BPM)
                        put(Resolved(supportLag, support, bpm, promotedFrom = baseBpm))
                    }
                }
            }
        }

        if (byBpm.isEmpty()) {
            return empty.copy(autocorrelation = ac)
        }

        data class Scored(val resolved: Resolved, val score: Float)

        val scored = byBpm.values.map { r ->
            Scored(r, r.raw * tempoPriorWeight(r.bpm.toFloat()))
        }.sortedByDescending { it.score }

        // Suggested pick for debug overlay only.
        var suggested = scored.first()
        val rawFloor = suggested.resolved.raw * COMPETITIVE_RAW_RATIO
        for (s in scored) {
            if (s.resolved.raw >= rawFloor && s.resolved.bpm > suggested.resolved.bpm) {
                suggested = s
            }
        }

        val winPeak = suggested.resolved.raw
        val (mean, std) = meanStd(ac, MIN_LAG, MAX_LAG)
        val denom = (std * 3f).coerceAtLeast(EPS)
        val confidence = ((winPeak - mean) / denom).coerceIn(0f, 1f)

        val candidates = scored.map { s ->
            DetectCandidate(
                bpm = s.resolved.bpm,
                lag = s.resolved.lag,
                rawPeak = s.resolved.raw,
                score = s.score,
                isWinner = s.resolved.bpm == suggested.resolved.bpm,
                promotedFrom = s.resolved.promotedFrom
            )
        }

        val options = if (confidence >= CONFIDENCE_FLOOR) {
            // Rank by score, then show ascending so half/double feels sit next to each other.
            scored.map { it.resolved.bpm }.distinct().take(MAX_OPTIONS).sorted()
        } else {
            emptyList()
        }

        return EstimateDebug(
            autocorrelation = ac,
            options = options,
            candidates = candidates,
            bpm = suggested.resolved.bpm,
            confidence = confidence,
            octaveDoubled = suggested.resolved.promotedFrom != null
        )
    }

    fun autocorrelation(envelope: FloatArray): FloatArray? {
        val n = envelope.size
        if (n <= MAX_LAG) return null

        var mean = 0f
        for (v in envelope) mean += v
        mean /= n

        val ac = FloatArray(MAX_LAG + 1)
        for (lag in MIN_LAG..MAX_LAG) {
            val count = n - lag
            var sum = 0f
            for (i in 0 until count) {
                sum += (envelope[i] - mean) * (envelope[i + lag] - mean)
            }
            ac[lag] = sum / count
        }
        return ac
    }

    private data class Peak(val lag: Int, val value: Float)

    private fun localMaxima(ac: FloatArray): List<Peak> {
        val out = ArrayList<Peak>()
        for (lag in MIN_LAG..MAX_LAG) {
            val v = ac[lag]
            val left = if (lag > MIN_LAG) ac[lag - 1] else Float.NEGATIVE_INFINITY
            val right = if (lag < MAX_LAG) ac[lag + 1] else Float.NEGATIVE_INFINITY
            if (v > left && v > right) {
                out.add(Peak(lag, v))
            }
        }
        return out
    }

    private fun tempoPriorWeight(bpm: Float): Float {
        val safe = bpm.coerceAtLeast(1f)
        val logRatio = ln(safe / PRIOR_CENTER_BPM) / ln(2f)
        val z = logRatio / PRIOR_SIGMA_OCT
        return exp(-0.5f * z * z)
    }

    private fun nearestLocalMaxLag(ac: FloatArray, targetLag: Float): Int? {
        val center = targetLag.roundToInt()
        val lo = (center - LAG_NEAR).coerceAtLeast(MIN_LAG)
        val hi = (center + LAG_NEAR).coerceAtMost(MAX_LAG)
        var bestLag: Int? = null
        var bestVal = -Float.MAX_VALUE
        for (lag in lo..hi) {
            val v = ac[lag]
            val left = if (lag > MIN_LAG) ac[lag - 1] else Float.NEGATIVE_INFINITY
            val right = if (lag < MAX_LAG) ac[lag + 1] else Float.NEGATIVE_INFINITY
            if (v > left && v > right && v > bestVal) {
                bestVal = v
                bestLag = lag
            }
        }
        return bestLag
    }

    private fun meanStd(ac: FloatArray, lo: Int, hi: Int): Pair<Float, Float> {
        val count = hi - lo + 1
        var sum = 0f
        for (i in lo..hi) sum += ac[i]
        val mean = sum / count
        var varSum = 0f
        for (i in lo..hi) {
            val d = ac[i] - mean
            varSum += d * d
        }
        return mean to sqrt(varSum / count)
    }

    private fun lagToBpm(lag: Int): Float =
        60f * OnsetEnvelope.ENVELOPE_RATE / lag

    private fun bpmToLag(bpm: Float): Float =
        60f * OnsetEnvelope.ENVELOPE_RATE / bpm
}
