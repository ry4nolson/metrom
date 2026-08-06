package com.metrom.app.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

enum class ClickTone(val label: String) {
    WOOD("Wood"),
    CLICK("Click"),
    BEEP("Beep"),
    RIM("Rim"),
    SOFT("Soft"),
    /** Palm-muted guitar chug. */
    CHUG("Chug")
}

/**
 * Pitch for ONE (accent) / OTHERS (non-accent) clicks.
 * Chromatic A3–E5. [hz] null = Off — use the tone's natural pitch.
 */
enum class AccentNote(val label: String, val hz: Double?) {
    OFF("Off", null),
    A3("A3", 220.00),
    AS3("A#3", 233.08),
    B3("B3", 246.94),
    C4("C4", 261.63),
    CS4("C#4", 277.18),
    D4("D4", 293.66),
    DS4("D#4", 311.13),
    E4("E4", 329.63),
    F4("F4", 349.23),
    FS4("F#4", 369.99),
    G4("G4", 392.00),
    GS4("G#4", 415.30),
    A4("A4", 440.00),
    AS4("A#4", 466.16),
    B4("B4", 493.88),
    C5("C5", 523.25),
    CS5("C#5", 554.37),
    D5("D5", 587.33),
    DS5("D#5", 622.25),
    E5("E5", 659.25);

    companion object {
        val DEFAULT = A4
    }
}

/**
 * Generates short PCM click samples in memory — no asset files needed.
 */
object ClickSynthesizer {
    const val SAMPLE_RATE = 44_100

    fun baseHz(tone: ClickTone): Double = when (tone) {
        ClickTone.WOOD -> 820.0
        ClickTone.CLICK -> 1650.0
        ClickTone.BEEP -> 880.0
        ClickTone.RIM -> 2100.0
        ClickTone.SOFT -> 640.0
        ClickTone.CHUG -> 123.47 // B2 — matches rendered samples
    }

    fun generate(
        tone: ClickTone,
        accent: Boolean,
        accentNote: AccentNote,
        restNote: AccentNote = AccentNote.OFF,
    ): ShortArray {
        if (tone == ClickTone.CHUG) {
            val root = when {
                accent && accentNote.hz != null -> accentNote.hz / 4.0
                !accent && restNote.hz != null -> restNote.hz / 4.0
                else -> baseHz(tone)
            }
            return chugSample(root, accent)
        }
        val hz = when {
            accent && accentNote.hz != null -> accentNote.hz
            !accent && restNote.hz != null -> restNote.hz
            else -> baseHz(tone)
        }
        val accented = accent && accentNote.hz != null
        return when (tone) {
            ClickTone.WOOD -> wood(hz, accented)
            ClickTone.CLICK -> click(hz, accented)
            ClickTone.BEEP -> beep(hz, accented)
            ClickTone.RIM -> rim(hz, accented)
            ClickTone.SOFT -> soft(hz, accented)
            ClickTone.CHUG -> chugSample(hz, accent)
        }
    }

    private fun wood(freq: Double, accent: Boolean): ShortArray {
        val durationMs = if (accent) 55 else 42
        val n = SAMPLE_RATE * durationMs / 1000
        val drive = if (accent) 2.2 else 2.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = exp(-t * 28.0) * (1.0 - exp(-t * 900.0))
            val body = sin(2.0 * PI * freq * t)
            val thump = sin(2.0 * PI * (freq * 0.5) * t) * 0.45
            val tip = sin(2.0 * PI * (freq * 2.0) * t) * exp(-t * 70.0) * 0.28
            val sample = tanh((body + thump + tip) * drive) * envelope
            toPcm(sample)
        }
    }

    private fun click(freq: Double, accent: Boolean): ShortArray {
        val durationMs = if (accent) 35 else 28
        val n = SAMPLE_RATE * durationMs / 1000
        val drive = if (accent) 2.4 else 2.2
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = exp(-t * 55.0) * (1.0 - exp(-t * 1200.0))
            val fundamental = sin(2.0 * PI * freq * t)
            val harmonic = sin(2.0 * PI * freq * 1.5 * t) * 0.35
            val sample = tanh((fundamental + harmonic) * drive) * envelope
            toPcm(sample)
        }
    }

    private fun beep(freq: Double, accent: Boolean): ShortArray {
        val durationMs = if (accent) 70 else 55
        val n = SAMPLE_RATE * durationMs / 1000
        val drive = if (accent) 1.7 else 1.6
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val attack = (i / (SAMPLE_RATE * 0.002)).coerceAtMost(1.0)
            val sustain = if (t < 0.025) 1.0 else exp(-((t - 0.025) * 40.0))
            val sample = tanh(sin(2.0 * PI * freq * t) * drive) * attack * sustain
            toPcm(sample)
        }
    }

    private fun rim(freq: Double, accent: Boolean): ShortArray {
        val durationMs = if (accent) 40 else 30
        val n = SAMPLE_RATE * durationMs / 1000
        val drive = if (accent) 2.6 else 2.3
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = exp(-t * 90.0) * (1.0 - exp(-t * 1800.0))
            val crack = sin(2.0 * PI * freq * t)
            val ring = sin(2.0 * PI * freq * 1.7 * t) * exp(-t * 40.0) * 0.4
            val noise = ((i * 1103515245 + 12345) and 0x7fff) / 32768.0 - 0.5
            val tip = noise * exp(-t * 220.0) * 0.55
            val sample = tanh((crack + ring + tip) * drive) * envelope
            toPcm(sample)
        }
    }

    private fun soft(freq: Double, accent: Boolean): ShortArray {
        val durationMs = if (accent) 80 else 60
        val n = SAMPLE_RATE * durationMs / 1000
        val drive = if (accent) 1.35 else 1.2
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = exp(-t * 18.0) * (1.0 - exp(-t * 400.0))
            val body = sin(2.0 * PI * freq * t)
            val bloom = sin(2.0 * PI * (freq * 0.5) * t) * 0.55
            val sample = tanh((body + bloom) * drive) * envelope
            toPcm(sample * 0.85)
        }
    }

    /**
     * Real (or fallback) chug one-shots from [ChugSamples].
     * Optional root pitch-shifts when ONE is set.
     */
    private fun chugSample(freq: Double, accent: Boolean): ShortArray {
        val source = if (accent) ChugSamples.accent else ChugSamples.normal
        val base = 123.47 // B2 — reference root for pitch shift
        val target = freq.coerceIn(82.0, 196.0)
        val ratio = (target / base).coerceIn(0.75, 1.35)
        if (kotlin.math.abs(ratio - 1.0) < 0.02) return source
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

    private fun toPcm(sample: Double): Short {
        val hot = (sample * 0.98).coerceIn(-1.0, 1.0)
        return (hot * Short.MAX_VALUE).toInt().toShort()
    }
}
