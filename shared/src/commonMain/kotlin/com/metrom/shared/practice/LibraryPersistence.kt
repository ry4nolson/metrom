package com.metrom.shared.practice

import com.metrom.shared.data.SetSection
import com.metrom.shared.data.Setlist
import com.metrom.shared.data.SongPreset
import com.metrom.shared.db.MetromDatabase
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.MutePattern
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.library.Section
import com.metrom.shared.library.SectionStore
import com.metrom.shared.library.SetlistStore
import com.metrom.shared.library.Song
import com.metrom.shared.library.SongStore
import com.metrom.shared.library.Setlist as LibrarySetlist

/**
 * Maps the controller's existing UI model onto the 2a library schema.
 *
 * Saved "songs" (public method names unchanged until 2b-ii) persist as standalone
 * [Section] rows — not library [Song]s.
 *
 * Each setlist slot is an implicit one-section [Song] wrapping that slot's [Section],
 * so [LibrarySetlist.songIds] can hold the ordered slots without a song-picker UX.
 * [Song.loop] temporarily stores [SetSection.autoAdvance] (no schema column for it).
 * 2b-ii replaces this with real multi-section songs.
 */
internal class LibraryPersistence(private val db: MetromDatabase) {
    private val sections = SectionStore(db)
    private val songs = SongStore(db)
    private val setlists = SetlistStore(db)

    fun loadSongPresets(): List<SongPreset> =
        sections.list().mapNotNull { section ->
            if (sections.referenceCount(section.id) != 0L) return@mapNotNull null
            section.toSongPreset()
        }

    fun loadUiSetlists(): List<Setlist> = setlists.list().map { lib ->
        Setlist(
            id = lib.id,
            name = lib.name,
            loop = lib.loop,
            sections = lib.songIds.mapNotNull { songId ->
                val song = songs.get(songId) ?: return@mapNotNull null
                val sectionId = song.sectionIds.firstOrNull() ?: return@mapNotNull null
                val section = sections.get(sectionId) ?: return@mapNotNull null
                section.toSetSection(autoAdvance = song.loop)
            },
        )
    }

    fun upsertSongPreset(preset: SongPreset) {
        sections.upsert(preset.toStandaloneSection())
    }

    fun deleteSongPreset(id: String) {
        sections.delete(id)
    }

    fun replaceSetlists(next: List<Setlist>) {
        db.transaction {
            val nextIds = next.map { it.id }.toSet()
            setlists.list().filter { it.id !in nextIds }.forEach { deleteUiSetlist(it.id) }
            next.forEach { writeUiSetlist(it) }
        }
    }

    private fun writeUiSetlist(setlist: Setlist) {
        val existing = setlists.get(setlist.id)
        val previousSongIds = existing?.songIds.orEmpty()
        val nextSongIds = setlist.sections.map { section ->
            sections.upsert(section.toLibrarySection())
            val songId = implicitSongId(section.id)
            songs.upsert(
                Song(
                    id = songId,
                    name = section.label ?: section.config.name,
                    loop = section.autoAdvance,
                    sectionIds = listOf(section.id),
                ),
            )
            songId
        }
        setlists.upsert(
            LibrarySetlist(
                id = setlist.id,
                name = setlist.name,
                loop = setlist.loop,
                pauseBetweenMs = existing?.pauseBetweenMs ?: 0,
                songIds = nextSongIds,
            ),
        )
        val keepSongIds = nextSongIds.toSet()
        val keepSectionIds = setlist.sections.map { it.id }.toSet()
        previousSongIds.filter { it !in keepSongIds }.forEach { orphanId ->
            val orphan = songs.get(orphanId)
            songs.delete(orphanId)
            orphan?.sectionIds
                ?.filter { it !in keepSectionIds }
                ?.forEach { sections.delete(it) }
        }
    }

    private fun deleteUiSetlist(id: String) {
        val existing = setlists.get(id) ?: return
        val sectionIds = existing.songIds.flatMap { songId ->
            songs.get(songId)?.sectionIds.orEmpty()
        }
        setlists.delete(id)
        existing.songIds.forEach { songs.delete(it) }
        sectionIds.forEach { sectionId ->
            if (sections.referenceCount(sectionId) == 0L) {
                sections.delete(sectionId)
            }
        }
    }
}

internal fun implicitSongId(sectionId: String): String = "slot:$sectionId"

private fun SongPreset.toStandaloneSection(): Section = Section(
    id = id,
    name = name,
    bars = 0,
    bpm = bpm,
    beats = timeSignature.beats,
    noteValue = timeSignature.noteValue,
    subdivision = subdivision,
    toneId = tone.id,
    accentNote = accentNote,
    restNote = restNote,
    beatAccents = beatAccents,
    swing = swing,
    groupTempo = groupTempo,
    countInBars = countInBars,
    mutePlayBars = mutePattern.playBars,
    muteSilentBars = mutePattern.silentBars,
)

private fun SetSection.toLibrarySection(): Section = Section(
    id = id,
    name = label,
    bars = bars,
    bpm = config.bpm,
    beats = config.timeSignature.beats,
    noteValue = config.timeSignature.noteValue,
    subdivision = config.subdivision,
    toneId = config.tone.id,
    accentNote = config.accentNote,
    restNote = config.restNote,
    beatAccents = config.beatAccents,
    swing = config.swing,
    groupTempo = config.groupTempo,
    countInBars = config.countInBars,
    mutePlayBars = config.mutePattern.playBars,
    muteSilentBars = config.mutePattern.silentBars,
)

private fun Section.toSongPreset(): SongPreset? {
    val tone = MetronomeTone.fromId(toneId) ?: return null
    return SongPreset(
        id = id,
        name = name ?: SongPreset.autoName(bpm, TimeSignature(beats, noteValue), subdivision),
        bpm = bpm,
        timeSignature = TimeSignature(beats, noteValue),
        subdivision = subdivision,
        tone = tone,
        accentNote = accentNote,
        restNote = restNote,
        beatAccents = beatAccents,
        swing = swing,
        groupTempo = groupTempo,
        countInBars = countInBars,
        mutePattern = MutePattern(mutePlayBars, muteSilentBars),
    )
}

private fun Section.toSetSection(autoAdvance: Boolean): SetSection? {
    val config = toSongPreset() ?: return null
    return SetSection(
        id = id,
        label = name,
        config = config,
        bars = bars,
        autoAdvance = autoAdvance,
    )
}
