package com.metrom.shared.domain

enum class ClickTone(val label: String) {
    WOOD("Wood"),
    CLICK("Click"),
    BEEP("Beep"),
    RIM("Rim"),
    SOFT("Soft"),
    CHUG("Chug"),
}

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

data class SampleTone(
    val id: String,
    val label: String,
    val assetDir: String,
    val rootHz: Double? = null,
)

object SampleToneRegistry {
    val all: List<SampleTone> = listOf(
        SampleTone(id = "chug", label = "Chug", assetDir = "tones/chug", rootHz = 123.47),
        SampleTone(id = "kick", label = "Kick", assetDir = "tones/kick"),
    )

    fun find(id: String): SampleTone? = all.find { it.id == id }
}

sealed class MetronomeTone {
    abstract val id: String
    abstract val label: String
    abstract val supportsPitchAccent: Boolean

    data class Synth(val tone: ClickTone) : MetronomeTone() {
        override val id: String get() = "synth:${tone.name}"
        override val label: String get() = tone.label
        override val supportsPitchAccent: Boolean get() = true
    }

    data class Sample(val tone: SampleTone) : MetronomeTone() {
        override val id: String get() = "sample:${tone.id}"
        override val label: String get() = tone.label
        override val supportsPitchAccent: Boolean get() = tone.rootHz != null
    }

    companion object {
        private val synthEntries: List<ClickTone> = listOf(
            ClickTone.WOOD,
            ClickTone.CLICK,
            ClickTone.BEEP,
            ClickTone.RIM,
            ClickTone.SOFT,
        )

        val all: List<MetronomeTone> =
            synthEntries.map(::Synth) + SampleToneRegistry.all.map(::Sample)

        fun fromId(id: String): MetronomeTone? = all.find { it.id == id }

        val DEFAULT: MetronomeTone = Synth(ClickTone.WOOD)
    }
}
