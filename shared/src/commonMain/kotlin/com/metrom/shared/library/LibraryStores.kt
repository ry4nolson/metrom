package com.metrom.shared.library

import com.metrom.shared.db.MetromDatabase
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.db.Section as SectionRow

class SectionStore(private val db: MetromDatabase) {
    fun list(): List<Section> = db.metromQueries.selectAllSections().executeAsList().map { it.toDomain() }

    fun get(id: String): Section? = db.metromQueries.selectSectionById(id).executeAsOneOrNull()?.toDomain()

    fun upsert(section: Section) {
        val row = db.metromQueries.selectSectionById(section.id).executeAsOneOrNull()
        if (row == null) {
            db.metromQueries.insertSection(
                id = section.id,
                name = section.name,
                bars = section.bars.toLong(),
                bpm = section.bpm.toLong(),
                beats = section.beats.toLong(),
                note_value = section.noteValue.toLong(),
                subdivision = section.subdivision.name,
                tone_id = section.toneId,
                accent_note = section.accentNote.name,
                rest_note = section.restNote.name,
                beat_accents = BeatAccent.encode(section.beatAccents),
                swing = section.swing.name,
                group_tempo = section.groupTempo.toLong(),
                count_in_bars = section.countInBars.toLong(),
                mute_play_bars = section.mutePlayBars.toLong(),
                mute_silent_bars = section.muteSilentBars.toLong(),
            )
        } else {
            db.metromQueries.updateSection(
                name = section.name,
                bars = section.bars.toLong(),
                bpm = section.bpm.toLong(),
                beats = section.beats.toLong(),
                note_value = section.noteValue.toLong(),
                subdivision = section.subdivision.name,
                tone_id = section.toneId,
                accent_note = section.accentNote.name,
                rest_note = section.restNote.name,
                beat_accents = BeatAccent.encode(section.beatAccents),
                swing = section.swing.name,
                group_tempo = section.groupTempo.toLong(),
                count_in_bars = section.countInBars.toLong(),
                mute_play_bars = section.mutePlayBars.toLong(),
                mute_silent_bars = section.muteSilentBars.toLong(),
                id = section.id,
            )
        }
    }

    fun delete(id: String) {
        db.metromQueries.deleteSectionById(id)
    }

    fun referenceCount(id: String): Long =
        db.metromQueries.countSongsUsingSection(id).executeAsOne()

    fun usage(id: String): Usage = Usage(
        referencedBy = db.metromQueries.selectSongsUsingSection(id).executeAsList().map {
            Referencer(id = it.id, name = it.name)
        },
    )
}

class SongStore(private val db: MetromDatabase) {
    fun list(): List<Song> = db.metromQueries.selectAllSongs().executeAsList().map { it.toDomain() }

    fun get(id: String): Song? = db.metromQueries.selectSongById(id).executeAsOneOrNull()?.toDomain()

    fun upsert(song: Song) {
        db.transaction {
            val row = db.metromQueries.selectSongById(song.id).executeAsOneOrNull()
            if (row == null) {
                db.metromQueries.insertSong(song.id, song.name, song.loop.toLong())
            } else {
                db.metromQueries.updateSong(song.name, song.loop.toLong(), song.id)
            }
            setSectionRefs(song.id, song.sectionRefs)
        }
    }

    fun delete(id: String) {
        db.metromQueries.deleteSongById(id)
    }

    fun setSections(songId: String, sectionIds: List<String>) {
        setSectionRefs(songId, sectionIds.map { SongSectionRef(it) })
    }

    fun setSectionRefs(songId: String, refs: List<SongSectionRef>) {
        db.transaction {
            db.metromQueries.clearSongSections(songId)
            refs.forEachIndexed { index, ref ->
                db.metromQueries.insertSongSection(
                    songId,
                    ref.sectionId,
                    index.toLong(),
                    ref.autoAdvance.toLong(),
                )
            }
        }
    }

    fun referenceCount(id: String): Long =
        db.metromQueries.countSetlistsUsingSong(id).executeAsOne()

    fun usage(id: String): Usage = Usage(
        referencedBy = db.metromQueries.selectSetlistsUsingSong(id).executeAsList().map {
            Referencer(id = it.id, name = it.name)
        },
    )

    private fun com.metrom.shared.db.Song.toDomain(): Song = Song(
        id = id,
        name = name,
        loop = loop.toBoolean(),
        sectionRefs = db.metromQueries.selectSongSectionRefs(id).executeAsList().map { row ->
            SongSectionRef(
                sectionId = row.section_id,
                autoAdvance = row.auto_advance.toBoolean(),
            )
        },
    )
}

class SetlistStore(private val db: MetromDatabase) {
    fun list(): List<Setlist> = db.metromQueries.selectAllSetlists().executeAsList().map { it.toDomain() }

    fun get(id: String): Setlist? = db.metromQueries.selectSetlistById(id).executeAsOneOrNull()?.toDomain()

    fun upsert(setlist: Setlist) {
        db.transaction {
            val row = db.metromQueries.selectSetlistById(setlist.id).executeAsOneOrNull()
            if (row == null) {
                db.metromQueries.insertSetlist(
                    setlist.id,
                    setlist.name,
                    setlist.loop.toLong(),
                    setlist.pauseBetweenMs.toLong(),
                )
            } else {
                db.metromQueries.updateSetlist(
                    setlist.name,
                    setlist.loop.toLong(),
                    setlist.pauseBetweenMs.toLong(),
                    setlist.id,
                )
            }
            setSongs(setlist.id, setlist.songIds)
        }
    }

    fun delete(id: String) {
        db.metromQueries.deleteSetlistById(id)
    }

    fun setSongs(setlistId: String, songIds: List<String>) {
        db.transaction {
            db.metromQueries.clearSetlistSongs(setlistId)
            songIds.forEachIndexed { index, songId ->
                db.metromQueries.insertSetlistSong(setlistId, songId, index.toLong())
            }
        }
    }

    private fun com.metrom.shared.db.Setlist.toDomain(): Setlist = Setlist(
        id = id,
        name = name,
        loop = loop.toBoolean(),
        pauseBetweenMs = pause_between_ms.toInt(),
        songIds = db.metromQueries.selectSongIdsForSetlist(id).executeAsList(),
    )
}

private fun SectionRow.toDomain(): Section = Section(
    id = id,
    name = name,
    bars = bars.toInt(),
    bpm = bpm.toInt(),
    beats = beats.toInt(),
    noteValue = note_value.toInt(),
    subdivision = enumValueOf<Subdivision>(subdivision),
    toneId = tone_id,
    accentNote = enumValueOf<AccentNote>(accent_note),
    restNote = enumValueOf<AccentNote>(rest_note),
    beatAccents = BeatAccent.decode(beat_accents, beats.toInt(), note_value.toInt()),
    swing = enumValueOf<SwingFeel>(swing),
    groupTempo = group_tempo.toBoolean(),
    countInBars = count_in_bars.toInt(),
    mutePlayBars = mute_play_bars.toInt(),
    muteSilentBars = mute_silent_bars.toInt(),
)

private fun Boolean.toLong(): Long = if (this) 1L else 0L

private fun Long.toBoolean(): Boolean = this != 0L
