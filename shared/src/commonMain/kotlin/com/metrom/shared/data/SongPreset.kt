package com.metrom.shared.data

import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.MutePattern
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.randomUuid

data class SongPreset(
    val id: String = randomUuid(),
    val name: String,
    val bpm: Int,
    val timeSignature: TimeSignature,
    val subdivision: Subdivision,
    val tone: MetronomeTone,
    val accentNote: AccentNote,
    val restNote: AccentNote = AccentNote.OFF,
    val beatAccents: List<BeatAccent> = BeatAccent.defaultPattern(timeSignature.beats, timeSignature.noteValue),
    val swing: SwingFeel = SwingFeel.OFF,
    val groupTempo: Boolean = false,
    val countInBars: Int = 0,
    val mutePattern: MutePattern = MutePattern.OFF,
) {
    fun sameSetupAs(
        bpm: Int,
        timeSignature: TimeSignature,
        subdivision: Subdivision,
        tone: MetronomeTone,
        accentNote: AccentNote,
        restNote: AccentNote,
        beatAccents: List<BeatAccent>,
        swing: SwingFeel,
        groupTempo: Boolean,
        countInBars: Int,
        mutePattern: MutePattern,
    ): Boolean = this.bpm == bpm &&
        this.timeSignature == timeSignature &&
        this.subdivision == subdivision &&
        this.tone == tone &&
        this.accentNote == accentNote &&
        this.restNote == restNote &&
        this.beatAccents == beatAccents &&
        this.swing == swing &&
        this.groupTempo == groupTempo &&
        this.countInBars == countInBars &&
        this.mutePattern == mutePattern

    companion object {
        fun autoName(bpm: Int, timeSignature: TimeSignature, subdivision: Subdivision): String =
            "$bpm · ${timeSignature.label} · ${subdivision.label}"
    }
}
