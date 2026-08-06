package com.metrom.app.engine

import kotlin.math.abs

/**
 * Lightweight resample pitch-shift for one-shot PCM (same approach as the old CHUG path).
 * Done at cache-build time — never inside the audio inner loop.
 */
object PcmPitch {
    /**
     * Pitch-shift [source] from [baseHz] toward [targetHz].
     * Ratio is clamped so one-shots stay musically usable and short.
     */
    fun shift(
        source: ShortArray,
        baseHz: Double,
        targetHz: Double,
        minHz: Double = 82.0,
        maxHz: Double = 196.0,
        minRatio: Double = 0.75,
        maxRatio: Double = 1.35,
    ): ShortArray {
        if (source.isEmpty()) return source
        val target = targetHz.coerceIn(minHz, maxHz)
        val ratio = (target / baseHz).coerceIn(minRatio, maxRatio)
        if (abs(ratio - 1.0) < 0.02) return source
        val outLen = (source.size / ratio).toInt().coerceAtLeast(64)
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, source.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(source.lastIndex)
            val frac = src - i0
            val a = source[i0].toDouble()
            val b = source[i1].toDouble()
            out[i] = (a + (b - a) * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    /**
     * Map a ONE/REST [AccentNote] mid-range pitch onto a low sample root (chug ≈ B2).
     * Matches the old ClickTone.CHUG convention: note.hz / 4.
     */
    fun sampleNoteHz(note: AccentNote, rootHz: Double): Double {
        val hz = note.hz ?: return rootHz
        return hz / 4.0
    }
}
