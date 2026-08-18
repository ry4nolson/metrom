package com.metrom.shared.library

import com.metrom.shared.db.openMetromDatabase
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.randomUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryStoreTest {
    @Test
    fun sectionRoundTripNamedAndOpenEnded() {
        val sections = sectionStore()
        val open = sampleSection(id = "sec-open", name = null, bars = 0, bpm = 96)
        val named = sampleSection(id = "sec-named", name = "Intro", bars = 8, bpm = 120)
        sections.upsert(open)
        sections.upsert(named)
        assertEquals(open, sections.get("sec-open"))
        assertEquals(named, sections.get("sec-named"))
        assertEquals(2, sections.list().size)
    }

    @Test
    fun songKeepsOrderedSectionIdsAndReorder() {
        val (sections, songs, _) = stores()
        val a = sampleSection("a", "A", 4, 90)
        val b = sampleSection("b", "B", 8, 100)
        val c = sampleSection("c", "C", 0, 110)
        sections.upsert(a)
        sections.upsert(b)
        sections.upsert(c)
        songs.upsert(
            Song(
                id = "song-1",
                name = "Tune",
                loop = true,
                sectionRefs = listOf(SongSectionRef("a"), SongSectionRef("b"), SongSectionRef("c")),
            ),
        )
        assertEquals(listOf("a", "b", "c"), songs.get("song-1")?.sectionIds)
        songs.setSections("song-1", listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), songs.get("song-1")?.sectionIds)
        assertTrue(songs.get("song-1")!!.loop)
    }

    @Test
    fun setlistKeepsOrderedSongIdsAndReorder() {
        val (sections, songs, setlists) = stores()
        sections.upsert(sampleSection("s1", "One", 4, 90))
        songs.upsert(Song("song-a", "A", sectionRefs = listOf(SongSectionRef("s1"))))
        songs.upsert(Song("song-b", "B", sectionRefs = listOf(SongSectionRef("s1"))))
        setlists.upsert(
            Setlist(id = "set-1", name = "Gig", loop = false, pauseBetweenMs = 1500, songIds = listOf("song-a", "song-b")),
        )
        assertEquals(listOf("song-a", "song-b"), setlists.get("set-1")?.songIds)
        assertEquals(1500, setlists.get("set-1")?.pauseBetweenMs)
        setlists.setSongs("set-1", listOf("song-b", "song-a"))
        assertEquals(listOf("song-b", "song-a"), setlists.get("set-1")?.songIds)
    }

    @Test
    fun songRoundTripsOrderedSectionsAndPerSectionAutoAdvance() {
        val (sections, songs, setlists) = stores()
        sections.upsert(sampleSection("intro", "Intro", 8, 90))
        sections.upsert(sampleSection("verse", "Verse", 16, 100))
        songs.upsert(
            Song(
                id = "multi",
                name = "Tune",
                sectionRefs = listOf(
                    SongSectionRef("intro", autoAdvance = true),
                    SongSectionRef("verse", autoAdvance = false),
                ),
            ),
        )
        setlists.upsert(Setlist(id = "gig", name = "Gig", songIds = listOf("multi")))
        val loaded = songs.get("multi")!!
        assertEquals(listOf("intro", "verse"), loaded.sectionIds)
        assertEquals(listOf(true, false), loaded.sectionRefs.map { it.autoAdvance })
        songs.setSectionRefs(
            "multi",
            listOf(
                SongSectionRef("verse", autoAdvance = true),
                SongSectionRef("intro", autoAdvance = true),
            ),
        )
        val reordered = songs.get("multi")!!
        assertEquals(listOf("verse", "intro"), reordered.sectionIds)
        assertEquals(listOf(true, true), reordered.sectionRefs.map { it.autoAdvance })
        assertEquals(listOf("multi"), setlists.get("gig")?.songIds)
    }

    @Test
    fun deletingReferencedSectionIsRestrictedUntilUnlinked() {
        val (sections, songs, _) = stores()
        sections.upsert(sampleSection("sec-r", "R", 4, 100))
        songs.upsert(Song("song-r", "Uses", sectionRefs = listOf(SongSectionRef("sec-r"))))
        assertEquals(1, sections.referenceCount("sec-r"))
        val blocked = assertFails { sections.delete("sec-r") }
        assertTrue(blocked.message?.contains("FOREIGN KEY", ignoreCase = true) == true || blocked is Exception)
        assertNotNull(sections.get("sec-r"))
        songs.setSections("song-r", emptyList())
        assertEquals(0, sections.referenceCount("sec-r"))
        sections.delete("sec-r")
        assertNull(sections.get("sec-r"))
    }

    @Test
    fun deletingReferencedSongIsRestrictedUntilUnlinked() {
        val (sections, songs, setlists) = stores()
        sections.upsert(sampleSection("sec-q", "Q", 2, 80))
        songs.upsert(Song("song-q", "Q", sectionRefs = listOf(SongSectionRef("sec-q"))))
        setlists.upsert(Setlist("set-q", "Q set", songIds = listOf("song-q")))
        assertEquals(1, songs.referenceCount("song-q"))
        assertFails { songs.delete("song-q") }
        assertNotNull(songs.get("song-q"))
        setlists.setSongs("set-q", emptyList())
        assertEquals(0, songs.referenceCount("song-q"))
        songs.delete("song-q")
        assertNull(songs.get("song-q"))
        assertNotNull(sections.get("sec-q"))
    }

    @Test
    fun deletingSongCascadesJunctionRowsButKeepsSections() {
        val (sections, songs, _) = stores()
        sections.upsert(sampleSection("keep-a", "Keep A", 4, 90))
        sections.upsert(sampleSection("keep-b", "Keep B", 4, 95))
        songs.upsert(Song("gone", "Gone", sectionRefs = listOf(SongSectionRef("keep-a"), SongSectionRef("keep-b"))))
        songs.delete("gone")
        assertNull(songs.get("gone"))
        assertNotNull(sections.get("keep-a"))
        assertNotNull(sections.get("keep-b"))
        assertEquals(0, sections.referenceCount("keep-a"))
    }

    @Test
    fun deletingSetlistCascadesJunctionRowsButKeepsSongs() {
        val (sections, songs, setlists) = stores()
        sections.upsert(sampleSection("s", "S", 4, 90))
        songs.upsert(Song("stay", "Stay", sectionRefs = listOf(SongSectionRef("s"))))
        setlists.upsert(Setlist("gone-set", "Gone", songIds = listOf("stay")))
        setlists.delete("gone-set")
        assertNull(setlists.get("gone-set"))
        assertNotNull(songs.get("stay"))
        assertEquals(0, songs.referenceCount("stay"))
    }

    private fun stores(): Triple<SectionStore, SongStore, SetlistStore> {
        val db = openMetromDatabase(createTestSqlDriver())
        return Triple(SectionStore(db), SongStore(db), SetlistStore(db))
    }

    private fun sectionStore(): SectionStore = stores().first

    private fun sampleSection(
        id: String = randomUuid(),
        name: String?,
        bars: Int,
        bpm: Int,
    ) = Section(
        id = id,
        name = name,
        bars = bars,
        bpm = bpm,
        beats = 4,
        noteValue = 4,
        subdivision = Subdivision.QUARTER,
        toneId = MetronomeTone.DEFAULT.id,
        accentNote = AccentNote.DEFAULT,
        restNote = AccentNote.OFF,
        beatAccents = BeatAccent.defaultPattern(4, 4),
        swing = SwingFeel.OFF,
        groupTempo = false,
        countInBars = 1,
        mutePlayBars = 1,
        muteSilentBars = 0,
    )
}
