package com.metrom.shared.data

import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.MutePattern
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.platform.PrefsStore
import com.metrom.shared.randomUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

@Serializable
private data class SongDto(
    val id: String,
    val name: String,
    val bpm: Int,
    val beats: Int,
    val noteValue: Int,
    val subdivision: Int,
    val toneId: String,
    val accentNote: Int,
    val restNote: Int = AccentNote.OFF.ordinal,
    val beatAccents: String? = null,
    val swing: Int = 0,
    val groupTempo: Boolean = false,
    val countInBars: Int = 0,
    val mutePlayBars: Int = 1,
    val muteSilentBars: Int = 0,
)

class SongStore(private val prefs: PrefsStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<SongPreset> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<SongDto>>(raw).mapNotNull { it.toPreset() }
        }.getOrElse {
            // Pre-release: discard corrupt state and reset.
            prefs.remove(KEY)
            emptyList()
        }
    }

    fun saveAll(songs: List<SongPreset>) {
        val dtos = songs.map { it.toDto() }
        prefs.putString(KEY, json.encodeToString(dtos))
    }

    private fun SongPreset.toDto() = SongDto(
        id = id,
        name = name,
        bpm = bpm,
        beats = timeSignature.beats,
        noteValue = timeSignature.noteValue,
        subdivision = subdivision.ordinal,
        toneId = tone.id,
        accentNote = accentNote.ordinal,
        restNote = restNote.ordinal,
        beatAccents = BeatAccent.encode(beatAccents),
        swing = swing.ordinal,
        groupTempo = groupTempo,
        countInBars = countInBars,
        mutePlayBars = mutePattern.playBars,
        muteSilentBars = mutePattern.silentBars,
    )

    private fun SongDto.toPreset(): SongPreset? {
        val tone = MetronomeTone.fromId(toneId) ?: return null
        return SongPreset(
            id = id,
            name = name,
            bpm = bpm,
            timeSignature = TimeSignature(beats, noteValue),
            subdivision = Subdivision.entries.getOrElse(subdivision) { Subdivision.QUARTER },
            tone = tone,
            accentNote = AccentNote.entries.getOrElse(accentNote) { AccentNote.DEFAULT },
            restNote = AccentNote.entries.getOrElse(restNote) { AccentNote.OFF },
            beatAccents = BeatAccent.decode(beatAccents, beats, noteValue),
            swing = SwingFeel.entries.getOrElse(swing) { SwingFeel.OFF },
            groupTempo = groupTempo,
            countInBars = countInBars,
            mutePattern = MutePattern(mutePlayBars, muteSilentBars),
        )
    }

    companion object {
        private const val KEY = "songs_json"
    }
}
