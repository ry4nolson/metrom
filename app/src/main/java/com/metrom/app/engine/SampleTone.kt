package com.metrom.app.engine

/**
 * Descriptor for a WAV-backed click tone.
 * Folder layout under assets: [assetDir]/strong.wav, normal.wav, optional ghost.wav
 * Adding a sound = one entry here + one asset folder. No engine edits.
 */
data class SampleTone(
    val id: String,
    val label: String,
    val assetDir: String,
)

object SampleToneRegistry {
    val all: List<SampleTone> = listOf(
        SampleTone(id = "chug", label = "Chug", assetDir = "tones/chug"),
        SampleTone(id = "kick", label = "Kick", assetDir = "tones/kick"),
        SampleTone(id = "snare", label = "Snare", assetDir = "tones/snare"),
    )

    fun find(id: String): SampleTone? = all.find { it.id == id }
}

/**
 * Unified tone selection model. UI/prefs use stable [id] strings; the audio loop
 * only ever receives pre-cached strong/normal/ghost ShortArrays for the active tone.
 */
sealed class MetronomeTone {
    abstract val id: String
    abstract val label: String

    data class Synth(val tone: ClickTone) : MetronomeTone() {
        override val id: String get() = "synth:${tone.name}"
        override val label: String get() = tone.label
    }

    data class Sample(val tone: SampleTone) : MetronomeTone() {
        override val id: String get() = "sample:${tone.id}"
        override val label: String get() = tone.label
    }

    companion object {
        /** Pure synth entries — CHUG is sample-backed and lives in [SampleToneRegistry]. */
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
