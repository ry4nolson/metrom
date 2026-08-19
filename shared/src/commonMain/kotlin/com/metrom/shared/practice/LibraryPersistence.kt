package com.metrom.shared.practice

import com.metrom.shared.db.MetromDatabase
import com.metrom.shared.library.Section
import com.metrom.shared.library.SectionStore
import com.metrom.shared.library.Setlist
import com.metrom.shared.library.SetlistStore
import com.metrom.shared.library.Song
import com.metrom.shared.library.SongSectionRef
import com.metrom.shared.library.SongStore
import com.metrom.shared.randomUuid

/** Computed playback/edit projection of a song section in flattened sequence order. */
data class SetlistSlot(
    val songId: String,
    val section: Section,
    val autoAdvance: Boolean,
)

internal class LibraryPersistence(private val db: MetromDatabase) {
    private val sections = SectionStore(db)
    private val songs = SongStore(db)
    private val setlists = SetlistStore(db)

    fun loadSections(): List<Section> = sections.list()
    fun loadSongs(): List<Song> = songs.list()
    fun loadSetlists(): List<Setlist> = setlists.list()

    fun getSection(id: String): Section? = sections.get(id)
    fun getSong(id: String): Song? = songs.get(id)

    fun upsertSection(section: Section) {
        sections.upsert(section)
    }

    fun sectionUsage(sectionId: String) = sections.usage(sectionId)

    fun songUsage(songId: String) = songs.usage(songId)

    fun deleteSection(id: String) {
        sections.delete(id)
    }

    fun deleteSong(id: String) {
        songs.delete(id)
    }

    fun upsertSong(song: Song) {
        songs.upsert(song)
    }

    fun upsertSetlist(setlist: Setlist) {
        setlists.upsert(setlist)
    }

    fun createSong(name: String, loop: Boolean = false): Song {
        val song = Song(id = randomUuid(), name = name, loop = loop)
        songs.upsert(song)
        return song
    }

    fun renameSong(id: String, name: String) {
        val current = songs.get(id) ?: return
        songs.upsert(current.copy(name = name))
    }

    fun setSongLoop(id: String, loop: Boolean) {
        val current = songs.get(id) ?: return
        songs.upsert(current.copy(loop = loop))
    }

    fun addSectionToSong(songId: String, section: Section, autoAdvance: Boolean = section.bars > 0): Section? {
        val song = songs.get(songId) ?: return null
        sections.upsert(section)
        songs.upsert(song.copy(sectionRefs = song.sectionRefs + SongSectionRef(section.id, autoAdvance)))
        return section
    }

    fun addExistingSectionToSong(songId: String, sectionId: String): Boolean {
        val song = songs.get(songId) ?: return false
        val section = sections.get(sectionId) ?: return false
        songs.upsert(
            song.copy(
                sectionRefs = song.sectionRefs + SongSectionRef(
                    sectionId,
                    autoAdvance = section.bars > 0,
                ),
            ),
        )
        return true
    }

    fun unlinkSectionFromSong(songId: String, sectionId: String) {
        val song = songs.get(songId) ?: return
        val idx = song.sectionRefs.indexOfFirst { it.sectionId == sectionId }
        if (idx < 0) return
        unlinkSectionFromSongAt(songId, idx)
    }

    fun unlinkSectionFromSongAt(songId: String, index: Int) {
        val song = songs.get(songId) ?: return
        if (index !in song.sectionRefs.indices) return
        val remaining = song.sectionRefs.toMutableList().also { it.removeAt(index) }
        songs.upsert(song.copy(sectionRefs = remaining))
    }

    fun moveSongSection(songId: String, from: Int, to: Int) {
        val song = songs.get(songId) ?: return
        val refs = song.sectionRefs.toMutableList()
        if (from !in refs.indices || to !in refs.indices) return
        val item = refs.removeAt(from)
        refs.add(to, item)
        songs.upsert(song.copy(sectionRefs = refs))
    }

    fun setSongSectionAutoAdvance(songId: String, sectionId: String, autoAdvance: Boolean) {
        val song = songs.get(songId) ?: return
        songs.upsert(
            song.copy(
                sectionRefs = song.sectionRefs.map {
                    if (it.sectionId == sectionId) it.copy(autoAdvance = autoAdvance) else it
                },
            ),
        )
    }

    fun setSongSectionAutoAdvanceAt(songId: String, index: Int, autoAdvance: Boolean) {
        val song = songs.get(songId) ?: return
        if (index !in song.sectionRefs.indices) return
        val refs = song.sectionRefs.toMutableList()
        refs[index] = refs[index].copy(autoAdvance = autoAdvance)
        songs.upsert(song.copy(sectionRefs = refs))
    }

    fun clearAutoAdvanceForSection(sectionId: String) {
        setAutoAdvanceForSection(sectionId, enabled = false)
    }

    fun enableAutoAdvanceForSection(sectionId: String) {
        setAutoAdvanceForSection(sectionId, enabled = true)
    }

    private fun setAutoAdvanceForSection(sectionId: String, enabled: Boolean) {
        songs.list().forEach { song ->
            if (song.sectionIds.none { it == sectionId }) return@forEach
            songs.upsert(
                song.copy(
                    sectionRefs = song.sectionRefs.map {
                        if (it.sectionId == sectionId) it.copy(autoAdvance = enabled) else it
                    },
                ),
            )
        }
    }

    fun getSetlist(id: String): Setlist? = setlists.get(id)

    fun createSetlist(name: String): Setlist {
        val setlist = Setlist(id = randomUuid(), name = name)
        setlists.upsert(setlist)
        return setlist
    }

    fun renameSetlist(id: String, name: String) {
        val current = setlists.get(id) ?: return
        setlists.upsert(current.copy(name = name))
    }

    fun setSetlistLoop(id: String, enabled: Boolean) {
        val current = setlists.get(id) ?: return
        setlists.upsert(current.copy(loop = enabled))
    }

    fun addSongToSetlist(setlistId: String, songId: String): Boolean {
        val setlist = setlists.get(setlistId) ?: return false
        if (songs.get(songId) == null) return false
        setlists.upsert(setlist.copy(songIds = setlist.songIds + songId))
        return true
    }

    fun removeSongFromSetlist(setlistId: String, songId: String) {
        val setlist = setlists.get(setlistId) ?: return
        val idx = setlist.songIds.indexOf(songId)
        if (idx < 0) return
        val remaining = setlist.songIds.toMutableList().also { it.removeAt(idx) }
        setlists.upsert(setlist.copy(songIds = remaining))
    }

    fun moveSetlistSong(setlistId: String, from: Int, to: Int) {
        val setlist = setlists.get(setlistId) ?: return
        val ids = setlist.songIds.toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        val item = ids.removeAt(from)
        ids.add(to, item)
        setlists.upsert(setlist.copy(songIds = ids))
    }

    /** Unlink only: songs and sections stay in the library. Junction rows cascade. */
    fun deleteSetlist(id: String) {
        setlists.delete(id)
    }
}
