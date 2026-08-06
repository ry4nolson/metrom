package com.metrom.shared.domain

object MetronomeLimits {
    const val MIN_BPM = 30
    const val MAX_BPM = 300
}

enum class Subdivision(val pulsesPerBeat: Int, val label: String) {
    QUARTER(1, "×1"),
    EIGHTH(2, "×2"),
    TRIPLET(3, "×3"),
    SIXTEENTH(4, "×4"),
}

enum class SwingFeel(val label: String, val longRatio: Float) {
    OFF("Off", 0.50f),
    LIGHT("Light", 0.58f),
    MED("Med", 0.67f),
    HEAVY("Heavy", 0.75f),
}

enum class BeatAccent(val code: Char) {
    MUTE('M'),
    NORMAL('N'),
    STRONG('S');

    fun next(): BeatAccent = when (this) {
        STRONG -> NORMAL
        NORMAL -> MUTE
        MUTE -> STRONG
    }

    companion object {
        fun defaultPattern(beats: Int, noteValue: Int = 4): List<BeatAccent> {
            val n = beats.coerceIn(1, 16)
            if (noteValue == 8 && n % 3 == 0) {
                return List(n) { if (it % 3 == 0) STRONG else NORMAL }
            }
            return List(n) { if (it == 0) STRONG else NORMAL }
        }

        fun encode(levels: List<BeatAccent>): String =
            levels.joinToString("") { it.code.toString() }

        fun decode(raw: String?, beats: Int, noteValue: Int = 4): List<BeatAccent> {
            val n = beats.coerceIn(1, 16)
            val defaults = defaultPattern(n, noteValue)
            if (raw.isNullOrBlank()) return defaults
            return List(n) { i ->
                when (raw.getOrNull(i)) {
                    'S' -> STRONG
                    'N' -> NORMAL
                    'M' -> MUTE
                    else -> defaults[i]
                }
            }
        }
    }
}

data class TimeSignature(val beats: Int, val noteValue: Int) {
    val label: String get() = "$beats/$noteValue"
    val isCompound: Boolean get() = noteValue == 8 && beats % 3 == 0

    companion object {
        val COMMON = listOf(
            TimeSignature(2, 4),
            TimeSignature(3, 4),
            TimeSignature(4, 4),
            TimeSignature(5, 4),
            TimeSignature(3, 8),
            TimeSignature(5, 8),
            TimeSignature(6, 8),
            TimeSignature(7, 8),
            TimeSignature(9, 8),
            TimeSignature(12, 8),
        )
    }
}

data class BeatEvent(
    val beatIndex: Int,
    val pulseIndex: Int,
    val isAccent: Boolean,
    val timestampMs: Long,
)

enum class SessionPhase {
    IDLE,
    COUNT_IN,
    PLAYING,
    SILENT,
    TRAINER_DONE,
}

data class MutePattern(val playBars: Int, val silentBars: Int) {
    val label: String
        get() = if (silentBars == 0) "Off" else "$playBars+$silentBars"

    companion object {
        val OFF = MutePattern(1, 0)
        val OPTIONS = listOf(
            OFF,
            MutePattern(1, 1),
            MutePattern(2, 2),
            MutePattern(4, 2),
            MutePattern(4, 4),
        )
    }
}
