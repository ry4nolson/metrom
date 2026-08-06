package com.metrom.shared.audio

import com.metrom.shared.domain.AccentNote
import kotlin.math.abs

object PcmPitch {
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

    fun sampleNoteHz(note: AccentNote, rootHz: Double): Double {
        val hz = note.hz ?: return rootHz
        return hz / 4.0
    }
}
