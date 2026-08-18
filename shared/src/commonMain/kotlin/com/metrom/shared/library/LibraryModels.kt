package com.metrom.shared.library

import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel

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
)

data class Song(
    val id: String,
    val name: String,
    val loop: Boolean = false,
    val sectionIds: List<String> = emptyList(),
)

data class Setlist(
    val id: String,
    val name: String,
    val loop: Boolean = false,
    val pauseBetweenMs: Int = 0,
    val songIds: List<String> = emptyList(),
)
