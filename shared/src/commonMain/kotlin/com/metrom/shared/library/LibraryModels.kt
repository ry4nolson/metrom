package com.metrom.shared.library

import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.MutePattern
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature

data class Section(
    val id: String,
    val name: String?,
    val bars: Int,
    val bpm: Int,
    val beats: Int,
    val noteValue: Int,
    val subdivision: Subdivision,
    val toneId: String,
    val accentNote: AccentNote,
    val restNote: AccentNote,
    val beatAccents: List<BeatAccent>,
    val swing: SwingFeel,
    val groupTempo: Boolean,
    val countInBars: Int,
    val mutePlayBars: Int,
    val muteSilentBars: Int,
) {
    val timeSignature: TimeSignature get() = TimeSignature(beats, noteValue)
    val mutePattern: MutePattern get() = MutePattern(mutePlayBars, muteSilentBars)

    fun displayName(): String = name?.takeIf { it.isNotBlank() } ?: autoName(bpm, timeSignature, subdivision)

    fun sameSetupAs(
        bpm: Int,
        timeSignature: TimeSignature,
        subdivision: Subdivision,
        toneId: String,
        accentNote: AccentNote,
        restNote: AccentNote,
        beatAccents: List<BeatAccent>,
        swing: SwingFeel,
        groupTempo: Boolean,
        countInBars: Int,
        mutePattern: MutePattern,
    ): Boolean = this.bpm == bpm &&
        this.beats == timeSignature.beats &&
        this.noteValue == timeSignature.noteValue &&
        this.subdivision == subdivision &&
        this.toneId == toneId &&
        this.accentNote == accentNote &&
        this.restNote == restNote &&
        this.beatAccents == beatAccents &&
        this.swing == swing &&
        this.groupTempo == groupTempo &&
        this.countInBars == countInBars &&
        this.mutePlayBars == mutePattern.playBars &&
        this.muteSilentBars == mutePattern.silentBars

    companion object {
        fun autoName(bpm: Int, timeSignature: TimeSignature, subdivision: Subdivision): String =
            "$bpm · ${timeSignature.label} · ${subdivision.label}"
    }
}

/** Junction payload: a section's place in a song, including whether it auto-advances. */
data class SongSectionRef(
    val sectionId: String,
    val autoAdvance: Boolean = false,
)

data class Song(
    val id: String,
    val name: String,
    val loop: Boolean = false,
    val sectionRefs: List<SongSectionRef> = emptyList(),
) {
    val sectionIds: List<String> get() = sectionRefs.map { it.sectionId }
}

data class Setlist(
    val id: String,
    val name: String,
    val loop: Boolean = false,
    val pauseBetweenMs: Int = 0,
    val songIds: List<String> = emptyList(),
)
