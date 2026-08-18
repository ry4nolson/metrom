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

/** Flattened setlist playback/edit slot: a song's section in setlist order. */
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

    fun upsertSection(section: Section) {
        sections.upsert(section)
    }

    fun deleteSection(id: String) {
        sections.delete(id)
    }

    fun upsertSong(song: Song) {
        songs.upsert(song)
    }

    fun upsertSetlist(setlist: Setlist) {
        setlists.upsert(setlist)
    }

    fun createSetlist(name: String): Setlist {
        val setlist = Setlist(id = randomUuid(), name = name)
        setlists.upsert(setlist)
        return setlist
    }

    fun renameSetlist(id: String, name: String) {
        val current = setlists.get(id) ?: return
        setlists.upsert(current.copy(name = name))
    }

    fun deleteSetlist(id: String) {
        db.transaction {
            val existing = setlists.get(id) ?: return@transaction
            val songIds = existing.songIds
            setlists.delete(id)
            songIds.forEach { songId ->
                if (songs.referenceCount(songId) == 0L) {
                    val sectionIds = songs.get(songId)?.sectionIds.orEmpty()
                    songs.delete(songId)
                    sectionIds.forEach { sectionId ->
                        if (sections.referenceCount(sectionId) == 0L) {
                            sections.delete(sectionId)
                        }
                    }
                }
            }
        }
    }

    fun addSlot(setlistId: String, section: Section, autoAdvance: Boolean = false): Song? {
        val setlist = setlists.get(setlistId) ?: return null
        sections.upsert(section)
        val song = Song(
            id = randomUuid(),
            name = section.displayName(),
            sectionRefs = listOf(SongSectionRef(section.id, autoAdvance)),
        )
        songs.upsert(song)
        setlists.upsert(setlist.copy(songIds = setlist.songIds + song.id))
        return song
    }

    fun removeSlot(setlistId: String, sectionId: String) {
        db.transaction {
            val setlist = setlists.get(setlistId) ?: return@transaction
            val songId = setlist.songIds.firstOrNull { id ->
                songs.get(id)?.sectionIds?.contains(sectionId) == true
            } ?: return@transaction
            val song = songs.get(songId) ?: return@transaction
            val remainingRefs = song.sectionRefs.filterNot { it.sectionId == sectionId }
            if (remainingRefs.isEmpty()) {
                setlists.upsert(setlist.copy(songIds = setlist.songIds.filterNot { it == songId }))
                songs.delete(songId)
            } else {
                songs.upsert(song.copy(sectionRefs = remainingRefs))
            }
            if (sections.referenceCount(sectionId) == 0L) {
                sections.delete(sectionId)
            }
        }
    }

    fun moveSlot(setlistId: String, from: Int, to: Int) {
        val setlist = setlists.get(setlistId) ?: return
        val slots = flatten(setlist)
        if (from !in slots.indices || to !in slots.indices) return
        val fromSongId = slots[from].songId
        val toSongId = slots[to].songId
        if (fromSongId == toSongId) {
            val song = songs.get(fromSongId) ?: return
            val refs = song.sectionRefs.toMutableList()
            val fromRef = refs.indexOfFirst { it.sectionId == slots[from].section.id }
            val toRef = refs.indexOfFirst { it.sectionId == slots[to].section.id }
            if (fromRef < 0 || toRef < 0) return
            val item = refs.removeAt(fromRef)
            refs.add(toRef, item)
            songs.upsert(song.copy(sectionRefs = refs))
            return
        }
        val songIds = setlist.songIds.toMutableList()
        val fromIndex = songIds.indexOf(fromSongId)
        val toIndex = songIds.indexOf(toSongId)
        if (fromIndex < 0 || toIndex < 0) return
        val item = songIds.removeAt(fromIndex)
        songIds.add(toIndex, item)
        setlists.upsert(setlist.copy(songIds = songIds))
    }

    fun setAutoAdvance(setlistId: String, sectionId: String, autoAdvance: Boolean) {
        val setlist = setlists.get(setlistId) ?: return
        val songId = setlist.songIds.firstOrNull { id ->
            songs.get(id)?.sectionIds?.contains(sectionId) == true
        } ?: return
        val song = songs.get(songId) ?: return
        songs.upsert(
            song.copy(
                sectionRefs = song.sectionRefs.map {
                    if (it.sectionId == sectionId) it.copy(autoAdvance = autoAdvance) else it
                },
            ),
        )
    }

    private fun flatten(setlist: Setlist): List<SetlistSlot> =
        setlist.songIds.flatMap { songId ->
            val song = songs.get(songId) ?: return@flatMap emptyList()
            song.sectionRefs.mapNotNull { ref ->
                val section = sections.get(ref.sectionId) ?: return@mapNotNull null
                SetlistSlot(songId, section, ref.autoAdvance)
            }
        }
}
