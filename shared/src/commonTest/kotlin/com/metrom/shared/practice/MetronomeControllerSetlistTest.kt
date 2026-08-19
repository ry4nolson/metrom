package com.metrom.shared.practice

import com.metrom.shared.audio.SampleToneCache
import com.metrom.shared.library.DeleteResult
import com.metrom.shared.library.Section
import com.metrom.shared.library.Setlist
import com.metrom.shared.library.Song
import com.metrom.shared.library.SongSectionRef
import com.metrom.shared.library.SectionStore
import com.metrom.shared.library.SetlistStore
import com.metrom.shared.library.SongStore
import com.metrom.shared.db.MetromDatabase
import com.metrom.shared.db.openMetromDatabase
import com.metrom.shared.library.createTestSqlDriver
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.BeatEvent
import com.metrom.shared.domain.ClickTone
import com.metrom.shared.domain.MetronomeLimits
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.engine.MetronomeEngine
import com.metrom.shared.platform.AssetIO
import com.metrom.shared.platform.AudioRouteHint
import com.metrom.shared.platform.AudioSink
import com.metrom.shared.platform.EngineRunner
import com.metrom.shared.platform.Haptics
import com.metrom.shared.platform.LatencyPad
import com.metrom.shared.platform.PrefsStore
import com.metrom.shared.platform.UiClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetronomeControllerSetlistTest {
    @Test
    fun nonLoopingSetEndStopsAndRearmsSectionZero() {
        val controller = controller()
        persistAndLoad(controller, threeSectionSet(autoBars = 2))
        val loadedId = controller.state.value.activeSetlistId
        controller.start()
        seedBar(controller)
        advanceBars(controller, 2)
        assertEquals(1, controller.state.value.activeSectionIndex)
        advanceBars(controller, 2)
        assertEquals(2, controller.state.value.activeSectionIndex)
        advanceBars(controller, 2)
        val s = controller.state.value
        assertFalse(s.isPlaying)
        assertEquals(0, s.activeSectionIndex)
        assertEquals(0, s.sectionBar)
        assertEquals(loadedId, s.activeSetlistId)
        assertTrue(s.inSetMode)
        assertEquals(90, s.bpm)
        assertEquals(TimeSignature(4, 4), s.timeSignature)
    }

    @Test
    fun previousSectionStepsBackAndClampsAtZero() {
        val controller = controller()
        persistAndLoad(controller, threeSectionSet())
        controller.advanceSection()
        controller.advanceSection()
        assertEquals(2, controller.state.value.activeSectionIndex)
        controller.previousSection()
        assertEquals(1, controller.state.value.activeSectionIndex)
        assertEquals(120, controller.state.value.bpm)
        controller.previousSection()
        assertEquals(0, controller.state.value.activeSectionIndex)
        controller.previousSection()
        assertEquals(0, controller.state.value.activeSectionIndex)
        assertEquals(90, controller.state.value.bpm)
        assertEquals(0, controller.state.value.sectionBar)
    }

    @Test
    fun restartSetFromSectionTwoLandsOnZero() {
        val controller = controller()
        persistAndLoad(controller, threeSectionSet())
        controller.advanceSection()
        controller.advanceSection()
        assertEquals(2, controller.state.value.activeSectionIndex)
        controller.restartSet()
        val s = controller.state.value
        assertEquals(0, s.activeSectionIndex)
        assertEquals(0, s.sectionBar)
        assertEquals(90, s.bpm)
        assertTrue(s.inSetMode)
    }

    @Test
    fun startFromStoppedLoadedSetAlwaysArmsSectionZero() {
        val controller = controller()
        persistAndLoad(controller, threeSectionSet())
        controller.advanceSection()
        controller.advanceSection()
        assertEquals(2, controller.state.value.activeSectionIndex)
        assertEquals(150, controller.state.value.bpm)
        assertFalse(controller.state.value.isPlaying)
        controller.start()
        val s = controller.state.value
        assertTrue(s.isPlaying)
        assertEquals(0, s.activeSectionIndex)
        assertEquals(0, s.sectionBar)
        assertEquals(90, s.bpm)
        assertEquals(TimeSignature(4, 4), s.timeSignature)
        assertEquals(Subdivision.QUARTER, s.subdivision)
    }

    @Test
    fun setSectionConfigMutatesTargetPersistsAndRoundTrips() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet())
        val slots = slots(h.controller)
        val a = slots[0].section.id
        val b = slots[1].section.id
        h.controller.setSectionBpm(b, 188)
        h.controller.setSectionTimeSignature(b, TimeSignature(5, 4))
        h.controller.setSectionSubdivision(b, Subdivision.SIXTEENTH)
        h.controller.setSectionSwing(b, SwingFeel.MED)
        h.controller.setSectionTone(b, MetronomeTone.Synth(ClickTone.BEEP))
        h.controller.setSectionAccentNote(b, AccentNote.C4)
        h.controller.setSectionRestNote(b, AccentNote.G4)
        h.controller.setSectionCountInBars(b, 2)
        h.controller.setSectionLabel(b, "Bridge")
        val stored = slots(h.controller)
        assertEquals(90, stored[0].section.bpm)
        assertEquals(188, stored[1].section.bpm)
        assertEquals(TimeSignature(5, 4), stored[1].section.timeSignature)
        assertEquals(Subdivision.SIXTEENTH, stored[1].section.subdivision)
        assertEquals(SwingFeel.MED, stored[1].section.swing)
        assertEquals(MetronomeTone.Synth(ClickTone.BEEP).id, stored[1].section.toneId)
        assertEquals(AccentNote.C4, stored[1].section.accentNote)
        assertEquals(AccentNote.G4, stored[1].section.restNote)
        assertEquals(2, stored[1].section.countInBars)
        assertEquals("Bridge", stored[1].section.name)
        assertEquals(a, stored[0].section.id)
        val reloaded = reloadSlots(h)
        assertEquals(188, reloaded[1].section.bpm)
        assertEquals(TimeSignature(5, 4), reloaded[1].section.timeSignature)
        assertEquals("Bridge", reloaded[1].section.name)
        assertEquals(90, reloaded[0].section.bpm)
    }

    @Test
    fun sectionConfigValidationMatchesTopLevelSetters() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet())
        val sectionId = slots(h.controller)[0].section.id
        h.controller.setSectionBpm(sectionId, 10)
        assertEquals(MetronomeLimits.MIN_BPM, sectionConfig(h.controller, 0).bpm)
        h.controller.setSectionBpm(sectionId, 400)
        assertEquals(MetronomeLimits.MAX_BPM, sectionConfig(h.controller, 0).bpm)
        h.controller.setSectionBeatAccents(
            sectionId,
            listOf(BeatAccent.STRONG, BeatAccent.MUTE),
        )
        assertEquals(4, sectionConfig(h.controller, 0).beatAccents.size)
        h.controller.setSectionBeatAccents(
            sectionId,
            List(8) { BeatAccent.MUTE },
        )
        assertEquals(4, sectionConfig(h.controller, 0).beatAccents.size)
        assertTrue(sectionConfig(h.controller, 0).beatAccents.all { it == BeatAccent.MUTE })
        h.controller.setSectionGroupTempo(sectionId, true)
        assertFalse(sectionConfig(h.controller, 0).groupTempo)
        h.controller.setSectionTimeSignature(sectionId, TimeSignature(6, 8))
        h.controller.setSectionGroupTempo(sectionId, true)
        assertTrue(sectionConfig(h.controller, 0).groupTempo)
        h.controller.setSectionTimeSignature(sectionId, TimeSignature(4, 4))
        assertFalse(sectionConfig(h.controller, 0).groupTempo)
        assertEquals(4, sectionConfig(h.controller, 0).beatAccents.size)
        h.controller.setSectionCountInBars(sectionId, 99)
        assertEquals(4, sectionConfig(h.controller, 0).countInBars)
        h.controller.setSectionLabel(sectionId, "  ")
        assertEquals(null, slots(h.controller)[0].section.name)
    }

    @Test
    fun setSectionBarsAcceptsFreeNumbersAndClamps() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet())
        val sectionId = slots(h.controller)[0].section.id
        listOf(3, 7, 12, 24).forEach { bars ->
            h.controller.setSectionBars(sectionId, bars)
            assertEquals(bars, slots(h.controller)[0].section.bars)
        }
        h.controller.setSectionBars(sectionId, 0)
        assertEquals(0, slots(h.controller)[0].section.bars)
        h.controller.setSectionBars(sectionId, -5)
        assertEquals(0, slots(h.controller)[0].section.bars)
        h.controller.setSectionBars(sectionId, 5000)
        assertEquals(999, slots(h.controller)[0].section.bars)
        val reloaded = reloadSlots(h)[0]
        assertEquals(999, reloaded.section.bars)
    }

    @Test
    fun captureCurrentIntoSectionOverwritesConfigKeepsMeta() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet(autoBars = 8))
        val target = slots(h.controller)[1]
        assertEquals("B", target.section.name)
        assertEquals(8, target.section.bars)
        assertTrue(target.autoAdvance)
        h.controller.setBpm(111)
        h.controller.setSubdivision(Subdivision.SIXTEENTH)
        h.controller.setSwing(SwingFeel.HEAVY)
        h.controller.captureCurrentIntoSection(target.section.id)
        val updated = slots(h.controller)[1]
        assertEquals(111, updated.section.bpm)
        assertEquals(Subdivision.SIXTEENTH, updated.section.subdivision)
        assertEquals(SwingFeel.HEAVY, updated.section.swing)
        assertEquals(8, updated.section.bars)
        assertTrue(updated.autoAdvance)
        assertEquals("B", updated.section.name)
        assertEquals(90, slots(h.controller)[0].section.bpm)
    }

    @Test
    fun editWhileLoadedStoppedReappliesPlayingDoesNotHotSwap() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet())
        val section0 = slots(h.controller)[0].section.id
        val section1 = slots(h.controller)[1].section.id
        assertEquals(90, h.controller.state.value.bpm)
        h.controller.setSectionBpm(section0, 144)
        assertEquals(144, h.controller.state.value.bpm)
        h.controller.setSectionBpm(section1, 160)
        assertEquals(144, h.controller.state.value.bpm)
        h.controller.start()
        assertEquals(144, h.controller.state.value.bpm)
        h.controller.setSectionBpm(section0, 200)
        assertEquals(144, h.controller.state.value.bpm)
        assertTrue(h.controller.state.value.isPlaying)
        assertEquals(200, sectionConfig(h.controller, 0).bpm)
    }

    @Test
    fun songWithTwoSectionsPreservesOrderAndAutoAdvanceAndSetlistRefsSongs() {
        val h = harness()
        val sections = SectionStore(h.database)
        val songs = SongStore(h.database)
        val setlists = SetlistStore(h.database)
        sections.upsert(sampleLibrarySection("sec-a", "A", 8, 90))
        sections.upsert(sampleLibrarySection("sec-b", "B", 16, 120))
        songs.upsert(
            Song(
                id = "song-multi",
                name = "Tune",
                sectionRefs = listOf(
                    SongSectionRef("sec-a", autoAdvance = true),
                    SongSectionRef("sec-b", autoAdvance = false),
                ),
            ),
        )
        setlists.upsert(Setlist(id = "set-1", name = "Gig", songIds = listOf("song-multi")))
        val loaded = makeController(h.prefs, h.database)
        val setlist = loaded.state.value.setlists.single()
        assertEquals(listOf("song-multi"), setlist.songIds)
        val slots = loaded.state.value.setlistSlots(setlist)
        assertEquals(2, slots.size)
        assertEquals(listOf("sec-a", "sec-b"), slots.map { it.section.id })
        assertEquals(listOf(true, false), slots.map { it.autoAdvance })
        assertEquals(listOf(8, 16), slots.map { it.section.bars })
        assertEquals("Tune", loaded.state.value.songs.single().name)
    }

    @Test
    fun deleteSectionBlockedWhileSongReferencesThenDeletedAfterUnlink() {
        val h = harness()
        val sections = SectionStore(h.database)
        val songs = SongStore(h.database)
        sections.upsert(sampleLibrarySection("sec-x", "Verse", 8, 100))
        songs.upsert(Song("song-x", "Tune", sectionRefs = listOf(SongSectionRef("sec-x"))))
        val controller = makeController(h.prefs, h.database)
        val section = controller.state.value.sections.single { it.id == "sec-x" }

        val usage = controller.sectionUsage("sec-x")
        assertEquals(1, usage.count)
        assertEquals("song-x", usage.referencedBy.single().id)
        assertEquals("Tune", usage.referencedBy.single().name)

        val blocked = controller.deleteSection(section)
        assertTrue(blocked is DeleteResult.Blocked)
        val blockedUsage = (blocked as DeleteResult.Blocked).usage
        assertEquals(1, blockedUsage.count)
        assertEquals("Tune", blockedUsage.referencedBy.single().name)
        assertEquals("song-x", blockedUsage.referencedBy.single().id)
        assertNotNull(SectionStore(h.database).get("sec-x"))

        songs.setSections("song-x", emptyList())
        val deleted = controller.deleteSection(section)
        assertEquals(DeleteResult.Deleted, deleted)
        assertNull(SectionStore(h.database).get("sec-x"))
    }

    @Test
    fun deleteSectionSucceedsAfterUnlinkFromSong() {
        val controller = controller()
        persistAndLoad(controller, listOf(threeSectionSet().first()))
        val songId = controller.state.value.setlists.single().songIds.single()
        val section = slots(controller).single().section
        val blocked = controller.deleteSection(section)
        assertTrue(blocked is DeleteResult.Blocked)
        assertEquals(1, (blocked as DeleteResult.Blocked).usage.count)
        assertNotNull(controller.state.value.sections.firstOrNull { it.id == section.id })

        controller.unlinkSectionFromSong(songId, section.id)
        assertEquals(listOf(songId), controller.state.value.setlists.single().songIds)
        assertTrue(controller.state.value.songs.first { it.id == songId }.sectionIds.isEmpty())
        assertNotNull(controller.state.value.sections.firstOrNull { it.id == section.id })

        val deleted = controller.deleteSection(section)
        assertEquals(DeleteResult.Deleted, deleted)
        assertTrue(controller.state.value.sections.none { it.id == section.id })
    }

    @Test
    fun deleteSongBlockedWhileSetlistReferencesThenDeletedAfterUnlink() {
        val h = harness()
        val sections = SectionStore(h.database)
        val songs = SongStore(h.database)
        val setlists = SetlistStore(h.database)
        sections.upsert(sampleLibrarySection("sec-y", "Y", 4, 90))
        songs.upsert(Song("song-y", "Q", sectionRefs = listOf(SongSectionRef("sec-y"))))
        setlists.upsert(Setlist("set-y", "Gig", songIds = listOf("song-y")))
        val controller = makeController(h.prefs, h.database)
        val song = controller.state.value.songs.single { it.id == "song-y" }

        val usage = controller.songUsage("song-y")
        assertEquals(1, usage.count)
        assertEquals("set-y", usage.referencedBy.single().id)
        assertEquals("Gig", usage.referencedBy.single().name)

        val blocked = controller.deleteSong(song)
        assertTrue(blocked is DeleteResult.Blocked)
        val blockedUsage = (blocked as DeleteResult.Blocked).usage
        assertEquals(1, blockedUsage.count)
        assertEquals("Gig", blockedUsage.referencedBy.single().name)
        assertNotNull(SongStore(h.database).get("song-y"))

        setlists.setSongs("set-y", emptyList())
        val deleted = controller.deleteSong(song)
        assertEquals(DeleteResult.Deleted, deleted)
        assertNull(SongStore(h.database).get("song-y"))
        assertNotNull(SectionStore(h.database).get("sec-y"))
    }

    @Test
    fun deleteSetlistCascadesSlotsKeepsSharedSongsAndCleansOrphanWrappers() {
        val h = harness()
        val sections = SectionStore(h.database)
        val songs = SongStore(h.database)
        val setlists = SetlistStore(h.database)
        sections.upsert(sampleLibrarySection("sec-shared", "Shared", 4, 90))
        songs.upsert(Song("song-shared", "Stay", sectionRefs = listOf(SongSectionRef("sec-shared"))))
        setlists.upsert(Setlist("set-a", "A", songIds = listOf("song-shared")))
        setlists.upsert(Setlist("set-b", "B", songIds = listOf("song-shared")))
        val controller = makeController(h.prefs, h.database)

        val shared = controller.deleteSetlist("set-a")
        assertEquals(DeleteResult.Deleted, shared)
        assertNull(SetlistStore(h.database).get("set-a"))
        assertNotNull(SongStore(h.database).get("song-shared"))
        assertEquals(listOf("song-shared"), SetlistStore(h.database).get("set-b")?.songIds)
        assertNotNull(SectionStore(h.database).get("sec-shared"))
    }

    @Test
    fun deleteSetlistKeepsMemberSongsAndSections() {
        val controller = controller()
        persistAndLoad(controller, listOf(threeSectionSet().first()))
        val setlistId = controller.state.value.setlists.single().id
        val songIds = controller.state.value.setlists.single().songIds
        val sectionIds = slots(controller).map { it.section.id }
        val cleaned = controller.deleteSetlist(setlistId)
        assertEquals(DeleteResult.Deleted, cleaned)
        assertTrue(controller.state.value.setlists.isEmpty())
        songIds.forEach { id -> assertNotNull(controller.state.value.songs.firstOrNull { it.id == id }) }
        sectionIds.forEach { id -> assertNotNull(controller.state.value.sections.firstOrNull { it.id == id }) }
    }

    @Test
    fun sectionScopedEditMutatesStandaloneSectionAndPersists() {
        val h = harness()
        h.controller.saveCurrentSection("Solo")
        val id = h.controller.state.value.savedSections.single().id
        h.controller.setSectionBpm(id, 142)
        h.controller.setSectionBars(id, 7)
        h.controller.setSectionLabel(id, "Verse")
        val stored = SectionStore(h.database).get(id)!!
        assertEquals(142, stored.bpm)
        assertEquals(7, stored.bars)
        assertEquals("Verse", stored.name)
        val reloaded = makeController(h.prefs, h.database)
        val section = reloaded.state.value.savedSections.single()
        assertEquals(142, section.bpm)
        assertEquals(7, section.bars)
        assertEquals("Verse", section.name)
    }

    @Test
    fun saveCurrentSectionAllowsSameConfigDuplicatesAndKeepsPlacedSectionsVisible() {
        val h = harness()
        h.controller.saveCurrentSection("First")
        h.controller.saveCurrentSection("Second")
        val song = h.controller.createSongFromCurrent("S")!!
        h.controller.addExistingSectionToSong(
            song.id,
            h.controller.state.value.sections.first { it.name == "First" }.id,
        )
        val sections = h.controller.state.value.sections
        val saved = h.controller.state.value.savedSections
        assertEquals(3, sections.size)
        assertEquals(sections.map { it.id }, saved.map { it.id })
        assertTrue(sections.any { it.name == "First" })
        assertTrue(sections.any { it.name == "Second" })
        assertEquals(sections.first { it.name == "First" }.bpm, sections.first { it.name == "Second" }.bpm)
    }

    @Test
    fun sectionScopedEditReappliesWhenLoadedStoppedNotWhilePlaying() {
        val h = harness()
        h.controller.saveCurrentSection("Live")
        val section = h.controller.state.value.savedSections.single()
        h.controller.loadSection(section)
        h.controller.setSectionBpm(section.id, 144)
        assertEquals(144, h.controller.state.value.bpm)
        h.controller.start()
        h.controller.setSectionBpm(section.id, 200)
        assertEquals(144, h.controller.state.value.bpm)
        assertTrue(h.controller.state.value.isPlaying)
        assertEquals(200, SectionStore(h.database).get(section.id)?.bpm)
    }

    @Test
    fun songCrudRoundTripsThroughStores() {
        val h = harness()
        val empty = h.controller.createSong("Empty")!!
        assertTrue(empty.sectionRefs.isEmpty())
        val live = h.controller.createSongFromCurrent("Live")!!
        assertEquals(1, live.sectionIds.size)
        h.controller.setBpm(88)
        h.controller.addSectionToSong(live.id)
        var song = h.controller.state.value.songs.first { it.id == live.id }
        assertEquals(2, song.sectionIds.size)
        h.controller.saveCurrentSection("Keep")
        val keep = h.controller.state.value.sections.first { it.name == "Keep" }
        h.controller.addExistingSectionToSong(live.id, keep.id)
        song = h.controller.state.value.songs.first { it.id == live.id }
        assertEquals(3, song.sectionIds.size)
        assertEquals(keep.id, song.sectionIds.last())
        h.controller.moveSongSection(live.id, 2, 0)
        song = h.controller.state.value.songs.first { it.id == live.id }
        assertEquals(keep.id, song.sectionIds.first())
        h.controller.setSectionBars(song.sectionIds[1], 4)
        h.controller.setSongSectionAutoAdvance(live.id, song.sectionIds[1], true)
        h.controller.renameSong(live.id, "Renamed")
        h.controller.setSongLoop(live.id, true)
        h.controller.unlinkSectionFromSong(live.id, keep.id)
        val stored = SongStore(h.database).get(live.id)!!
        assertEquals("Renamed", stored.name)
        assertTrue(stored.loop)
        assertEquals(2, stored.sectionIds.size)
        assertTrue(keep.id !in stored.sectionIds)
        assertTrue(stored.sectionRefs[0].autoAdvance)
        assertNotNull(SectionStore(h.database).get(keep.id))
    }

    @Test
    fun songReusesTheSameSectionInPlayOrder() {
        val h = harness()
        listOf("Intro", "Verse", "Chorus", "Bridge", "Outro").forEach { name ->
            h.controller.saveCurrentSection(name)
        }
        val byName = h.controller.state.value.sections.associateBy { it.name }
        val intro = byName["Intro"]!!.id
        val verse = byName["Verse"]!!.id
        val chorus = byName["Chorus"]!!.id
        val bridge = byName["Bridge"]!!.id
        val outro = byName["Outro"]!!.id
        val song = h.controller.createSong("Tune")!!
        listOf(intro, verse, chorus, bridge, verse, chorus, outro).forEach { sectionId ->
            h.controller.addExistingSectionToSong(song.id, sectionId)
        }
        val ordered = h.controller.state.value.songs.first { it.id == song.id }
        assertEquals(
            listOf(intro, verse, chorus, bridge, verse, chorus, outro),
            ordered.sectionIds,
        )

        h.controller.setSectionBars(verse, 2)
        h.controller.setSongSectionAutoAdvanceAt(song.id, 1, true)
        h.controller.setSongSectionAutoAdvanceAt(song.id, 4, false)
        val refs = h.controller.state.value.songs.first { it.id == song.id }.sectionRefs
        assertTrue(refs[1].autoAdvance)
        assertFalse(refs[4].autoAdvance)

        h.controller.loadSong(h.controller.state.value.songs.first { it.id == song.id })
        assertEquals(
            listOf(intro, verse, chorus, bridge, verse, chorus, outro),
            h.controller.state.value.activeSlots().map { it.section.id },
        )

        h.controller.unlinkSectionFromSongAt(song.id, 5)
        val after = h.controller.state.value.songs.first { it.id == song.id }
        assertEquals(listOf(intro, verse, chorus, bridge, verse, outro), after.sectionIds)
        assertEquals(2, after.sectionIds.count { it == verse })
        assertEquals(1, after.sectionIds.count { it == chorus })
        assertEquals(5, h.controller.state.value.sections.size)
    }

    @Test
    fun standaloneSongOfThreeAutoSectionsAdvancesOnPlay() {
        val h = harness()
        h.controller.createSetlist("Set 1")
        val setlistId = h.controller.state.value.setlists.single().id
        val song = h.controller.createSong("Tune")!!
        listOf(90, 100, 110).forEach { bpm ->
            h.controller.setBpm(bpm)
            h.controller.addSectionToSong(song.id)
            val ids = h.controller.state.value.songs.first { it.id == song.id }.sectionIds
            val sectionId = ids.last()
            h.controller.setSectionBpm(sectionId, bpm)
            h.controller.setSectionBars(sectionId, 2)
            h.controller.setSongSectionAutoAdvanceAt(song.id, ids.lastIndex, true)
        }
        h.controller.addSongToSetlist(setlistId, song.id)
        val loaded = h.controller.state.value.songs.first { it.id == song.id }
        val slots = h.controller.state.value.songSlots(loaded)
        assertEquals(listOf(true, true, true), slots.map { it.autoAdvance })
        assertEquals(listOf(2, 2, 2), slots.map { it.section.bars })

        h.controller.loadSong(loaded)
        assertNull(h.controller.state.value.activeSetlistId)
        assertEquals(song.id, h.controller.state.value.activeSongId)
        assertEquals(3, h.controller.state.value.activeSlots().size)
        assertEquals(3, h.controller.state.value.setlistSlots(h.controller.state.value.setlists.single()).size)

        h.controller.start()
        seedBar(h.controller)
        val bpms = mutableListOf(h.controller.state.value.bpm)
        repeat(2) {
            advanceBars(h.controller, 2)
            bpms += h.controller.state.value.bpm
        }
        assertEquals(listOf(90, 100, 110), bpms)
        assertEquals(2, h.controller.state.value.activeSectionIndex)
        assertNull(h.controller.state.value.activeSetlistId)
    }

    @Test
    fun addingTimedSavedSectionDefaultsAutoAndAdvancesTwoFourIntro() {
        val h = harness()
        h.controller.setBpm(120)
        h.controller.setTimeSignature(TimeSignature(2, 4))
        h.controller.saveCurrentSection("intro")
        val introId = h.controller.state.value.sections.single { it.name == "intro" }.id
        h.controller.setSectionBars(introId, 2)
        h.controller.setBpm(130)
        h.controller.setTimeSignature(TimeSignature(4, 4))
        h.controller.saveCurrentSection("verse")
        val verseId = h.controller.state.value.sections.single { it.name == "verse" }.id
        h.controller.setSectionBars(verseId, 2)
        val song = h.controller.createSong("test song")!!
        h.controller.addExistingSectionToSong(song.id, introId)
        h.controller.addExistingSectionToSong(song.id, verseId)
        val slots = h.controller.state.value.songSlots(
            h.controller.state.value.songs.first { it.id == song.id },
        )
        assertEquals(listOf(true, true), slots.map { it.autoAdvance })
        assertEquals(listOf(2, 2), slots.map { it.section.bars })

        h.controller.loadSong(h.controller.state.value.songs.first { it.id == song.id })
        h.controller.start()
        seedBar(h.controller, beatsPerBar = 2)
        assertEquals(0, h.controller.state.value.activeSectionIndex)
        assertEquals(120, h.controller.state.value.bpm)
        advanceBars(h.controller, 2, beatsPerBar = 2)
        assertEquals(1, h.controller.state.value.activeSectionIndex)
        assertEquals(130, h.controller.state.value.bpm)
    }

    @Test
    fun reusedSectionRespectsPerPlacementAutoAdvance() {
        val h = harness()
        h.controller.saveCurrentSection("Verse")
        val verseId = h.controller.state.value.sections.single().id
        h.controller.setSectionBars(verseId, 2)
        h.controller.setSectionBpm(verseId, 90)
        h.controller.saveCurrentSection("Chorus")
        val chorusId = h.controller.state.value.sections.first { it.name == "Chorus" }.id
        h.controller.setSectionBars(chorusId, 2)
        h.controller.setSectionBpm(chorusId, 120)
        val song = h.controller.createSong("Tune")!!
        h.controller.addExistingSectionToSong(song.id, verseId)
        h.controller.addExistingSectionToSong(song.id, verseId)
        h.controller.addExistingSectionToSong(song.id, chorusId)
        h.controller.setSongSectionAutoAdvanceAt(song.id, 0, true)
        h.controller.setSongSectionAutoAdvanceAt(song.id, 1, false)
        h.controller.setSongSectionAutoAdvanceAt(song.id, 2, true)
        val slots = h.controller.state.value.songSlots(
            h.controller.state.value.songs.first { it.id == song.id },
        )
        assertEquals(listOf(true, false, true), slots.map { it.autoAdvance })
        assertEquals(listOf(verseId, verseId, chorusId), slots.map { it.section.id })

        h.controller.loadSong(h.controller.state.value.songs.first { it.id == song.id })
        h.controller.start()
        seedBar(h.controller)
        advanceBars(h.controller, 2)
        assertEquals(1, h.controller.state.value.activeSectionIndex)
        assertEquals(90, h.controller.state.value.bpm)
        advanceBars(h.controller, 8)
        assertEquals(1, h.controller.state.value.activeSectionIndex)
        assertTrue(h.controller.state.value.isPlaying)
        h.controller.advanceSection()
        advanceBars(h.controller, 1)
        assertEquals(2, h.controller.state.value.activeSectionIndex)
        assertEquals(120, h.controller.state.value.bpm)
    }

    @Test
    fun openEndedSectionHoldsUntilManualAdvance() {
        val h = harness()
        val song = h.controller.createSong("Tune")!!
        h.controller.setBpm(90)
        h.controller.addSectionToSong(song.id)
        h.controller.setBpm(110)
        h.controller.addSectionToSong(song.id)
        val ids = h.controller.state.value.songs.first { it.id == song.id }.sectionIds
        h.controller.setSectionBpm(ids[0], 90)
        h.controller.setSectionBars(ids[0], 0)
        h.controller.setSongSectionAutoAdvanceAt(song.id, 0, true)
        h.controller.setSectionBpm(ids[1], 110)
        h.controller.setSectionBars(ids[1], 2)
        h.controller.setSongSectionAutoAdvanceAt(song.id, 1, true)
        val first = h.controller.state.value.songSlots(
            h.controller.state.value.songs.first { it.id == song.id },
        ).first()
        assertEquals(0, first.section.bars)
        assertFalse(first.autoAdvance)

        h.controller.loadSong(h.controller.state.value.songs.first { it.id == song.id })
        h.controller.start()
        seedBar(h.controller)
        advanceBars(h.controller, 8)
        assertEquals(0, h.controller.state.value.activeSectionIndex)
        assertEquals(90, h.controller.state.value.bpm)
        h.controller.advanceSection()
        advanceBars(h.controller, 1)
        assertEquals(1, h.controller.state.value.activeSectionIndex)
        assertEquals(110, h.controller.state.value.bpm)
    }

    @Test
    fun loadSongBuildsSongOnlySequenceNotSetlistFlatten() {
        val h = harness()
        val sections = SectionStore(h.database)
        val songs = SongStore(h.database)
        val setlists = SetlistStore(h.database)
        sections.upsert(sampleLibrarySection("a1", "A1", 2, 90))
        sections.upsert(sampleLibrarySection("a2", "A2", 2, 100))
        sections.upsert(sampleLibrarySection("b1", "B1", 2, 110))
        songs.upsert(
            Song("song-a", "A", sectionRefs = listOf(SongSectionRef("a1", true), SongSectionRef("a2", true))),
        )
        songs.upsert(
            Song("song-b", "B", sectionRefs = listOf(SongSectionRef("b1", true))),
        )
        setlists.upsert(Setlist("set-1", "Set 1", songIds = listOf("song-a", "song-b")))
        val controller = makeController(h.prefs, h.database)
        val songA = controller.state.value.songs.first { it.id == "song-a" }
        controller.loadSong(songA)
        assertNull(controller.state.value.activeSetlistId)
        assertEquals("song-a", controller.state.value.activeSongId)
        assertEquals(2, controller.state.value.activeSlots().size)
        assertEquals(3, controller.state.value.setlistSlots(controller.state.value.setlists.single()).size)
    }

    @Test
    fun autoAdvanceFiresWhenOnlyDownbeatsArrive() {
        val h = harness()
        val song = h.controller.createSong("Tune")!!
        h.controller.setBpm(90)
        h.controller.addSectionToSong(song.id)
        h.controller.setBpm(120)
        h.controller.addSectionToSong(song.id)
        val ids = h.controller.state.value.songs.first { it.id == song.id }.sectionIds
        h.controller.setSectionBpm(ids[0], 90)
        h.controller.setSectionBars(ids[0], 2)
        h.controller.setSongSectionAutoAdvanceAt(song.id, 0, true)
        h.controller.setSectionBpm(ids[1], 120)
        h.controller.setSectionBars(ids[1], 2)
        h.controller.setSongSectionAutoAdvanceAt(song.id, 1, true)
        h.controller.loadSong(h.controller.state.value.songs.first { it.id == song.id })
        h.controller.start()
        h.controller.handleBeat(BeatEvent(0, 0, true, 0L))
        assertEquals(0, h.controller.state.value.activeSectionIndex)
        repeat(2) { h.controller.handleBeat(BeatEvent(0, 0, true, 0L)) }
        assertEquals(1, h.controller.state.value.activeSectionIndex)
        assertEquals(120, h.controller.state.value.bpm)
    }

    @Test
    fun loadSongAdvancesAutoSectionThenHoldsOpenEndedThenLoopsOrStops() {
        val h = harness()
        val sections = SectionStore(h.database)
        val songs = SongStore(h.database)
        sections.upsert(sampleLibrarySection("a", "A", 2, 90))
        sections.upsert(sampleLibrarySection("b", "B", 0, 120))
        songs.upsert(
            Song(
                id = "tune",
                name = "Tune",
                loop = false,
                sectionRefs = listOf(
                    SongSectionRef("a", autoAdvance = true),
                    SongSectionRef("b", autoAdvance = false),
                ),
            ),
        )
        val controller = makeController(h.prefs, h.database)
        controller.loadSong(controller.state.value.songs.single())
        assertEquals("tune", controller.state.value.activeSongId)
        assertEquals(0, controller.state.value.activeSectionIndex)
        assertEquals(90, controller.state.value.bpm)
        assertFalse(controller.state.value.isPlaying)

        controller.start()
        seedBar(controller)
        advanceBars(controller, 2)
        assertEquals(1, controller.state.value.activeSectionIndex)
        assertEquals(120, controller.state.value.bpm)
        advanceBars(controller, 8)
        assertEquals(1, controller.state.value.activeSectionIndex)
        assertTrue(controller.state.value.isPlaying)
        controller.advanceSection()
        advanceBars(controller, 1)
        assertFalse(controller.state.value.isPlaying)
        assertEquals(0, controller.state.value.activeSectionIndex)
        assertEquals(90, controller.state.value.bpm)

        val looping = makeController(h.prefs, h.database)
        looping.setSectionBars("b", 2)
        looping.setSongSectionAutoAdvance("tune", "b", true)
        looping.setSongLoop("tune", true)
        looping.loadSong(looping.state.value.songs.single())
        looping.start()
        seedBar(looping)
        advanceBars(looping, 2)
        assertEquals(1, looping.state.value.activeSectionIndex)
        advanceBars(looping, 2)
        assertTrue(looping.state.value.isPlaying)
        assertEquals(0, looping.state.value.activeSectionIndex)
        assertEquals(90, looping.state.value.bpm)
    }

    @Test
    fun setlistOfTwoSongsAdvancesSectionThenSongThenLoopsOrStops() {
        val h = harness()
        val sections = SectionStore(h.database)
        val songs = SongStore(h.database)
        val setlists = SetlistStore(h.database)
        sections.upsert(sampleLibrarySection("s1a", "A", 2, 90))
        sections.upsert(sampleLibrarySection("s1b", "B", 2, 100))
        sections.upsert(sampleLibrarySection("s2a", "C", 2, 110))
        sections.upsert(sampleLibrarySection("s2b", "D", 2, 120))
        songs.upsert(
            Song(
                id = "song-1",
                name = "One",
                sectionRefs = listOf(SongSectionRef("s1a", true), SongSectionRef("s1b", true)),
            ),
        )
        songs.upsert(
            Song(
                id = "song-2",
                name = "Two",
                sectionRefs = listOf(SongSectionRef("s2a", true), SongSectionRef("s2b", true)),
            ),
        )
        setlists.upsert(Setlist("set-x", "Gig", loop = false, songIds = listOf("song-1", "song-2")))
        val controller = makeController(h.prefs, h.database)
        controller.loadSetlist(controller.state.value.setlists.single())
        controller.start()
        seedBar(controller)
        val bpms = mutableListOf(controller.state.value.bpm)
        repeat(3) {
            advanceBars(controller, 2)
            bpms += controller.state.value.bpm
        }
        assertEquals(listOf(90, 100, 110, 120), bpms)
        assertEquals(3, controller.state.value.activeSectionIndex)
        advanceBars(controller, 2)
        assertFalse(controller.state.value.isPlaying)
        assertEquals(0, controller.state.value.activeSectionIndex)
        assertEquals(90, controller.state.value.bpm)

        setlists.upsert(Setlist("set-x", "Gig", loop = true, songIds = listOf("song-1", "song-2")))
        val looping = makeController(h.prefs, h.database)
        looping.loadSetlist(looping.state.value.setlists.single())
        looping.start()
        seedBar(looping)
        repeat(4) { advanceBars(looping, 2) }
        assertTrue(looping.state.value.isPlaying)
        assertEquals(0, looping.state.value.activeSectionIndex)
        assertEquals(90, looping.state.value.bpm)
    }

    @Test
    fun songMembershipRoundTripsThroughStorePreservingOrder() {
        val h = harness()
        val a = h.controller.createSong("A")!!
        val b = h.controller.createSong("B")!!
        val c = h.controller.createSong("C")!!
        h.controller.createSetlist("Gig")
        val setlistId = h.controller.state.value.setlists.single().id
        h.controller.addSongToSetlist(setlistId, a.id)
        h.controller.addSongToSetlist(setlistId, b.id)
        h.controller.addSongToSetlist(setlistId, c.id)
        assertEquals(listOf(a.id, b.id, c.id), h.controller.state.value.setlists.single().songIds)
        h.controller.moveSetlistSong(setlistId, 2, 0)
        assertEquals(listOf(c.id, a.id, b.id), h.controller.state.value.setlists.single().songIds)
        h.controller.removeSongFromSetlist(setlistId, a.id)
        assertEquals(listOf(c.id, b.id), h.controller.state.value.setlists.single().songIds)
        assertNotNull(h.controller.state.value.songs.firstOrNull { it.id == a.id })
        h.controller.setSetlistLoop(setlistId, true)
        val stored = SetlistStore(h.database).get(setlistId)!!
        assertEquals(listOf(c.id, b.id), stored.songIds)
        assertTrue(stored.loop)
        assertTrue(h.controller.state.value.setlists.single().loop)
    }

    @Test
    fun songInTwoSetlistsUnlinkLeavesOtherAndLibrary() {
        val h = harness()
        val song = h.controller.createSong("Shared")!!
        h.controller.createSetlist("A")
        h.controller.createSetlist("B")
        val a = h.controller.state.value.setlists.first { it.name == "A" }.id
        val b = h.controller.state.value.setlists.first { it.name == "B" }.id
        h.controller.addSongToSetlist(a, song.id)
        h.controller.addSongToSetlist(b, song.id)
        h.controller.removeSongFromSetlist(a, song.id)
        assertTrue(h.controller.state.value.setlists.first { it.id == a }.songIds.isEmpty())
        assertEquals(listOf(song.id), h.controller.state.value.setlists.first { it.id == b }.songIds)
        assertNotNull(h.controller.state.value.songs.firstOrNull { it.id == song.id })
        assertNotNull(SongStore(h.database).get(song.id))
    }

    @Test
    fun addSongToSetlistDrivesPlaybackSequenceFromSongIds() {
        val h = harness()
        h.controller.createSetlist("Gig")
        val setlistId = h.controller.state.value.setlists.single().id
        val song1 = createTwoSectionSong(h.controller, "One", 90, 100)
        h.controller.addSongToSetlist(setlistId, song1)
        h.controller.loadSetlist(h.controller.state.value.setlists.single())
        assertEquals(2, h.controller.state.value.activeSlots().size)
        assertEquals(listOf(90, 100), h.controller.state.value.activeSlots().map { it.section.bpm })

        val song2 = createTwoSectionSong(h.controller, "Two", 110, 120)
        h.controller.addSongToSetlist(setlistId, song2)
        val afterAdd = h.controller.state.value.activeSlots()
        assertEquals(listOf(song1, song2), h.controller.state.value.setlists.single().songIds)
        assertEquals(4, afterAdd.size)
        assertEquals(listOf(90, 100, 110, 120), afterAdd.map { it.section.bpm })
        assertEquals(listOf(song1, song1, song2, song2), afterAdd.map { it.songId })

        h.controller.start()
        seedBar(h.controller)
        val bpms = mutableListOf(h.controller.state.value.bpm)
        repeat(3) {
            advanceBars(h.controller, 2)
            bpms += h.controller.state.value.bpm
        }
        assertEquals(listOf(90, 100, 110, 120), bpms)
        assertEquals(3, h.controller.state.value.activeSectionIndex)
        advanceBars(h.controller, 2)
        assertFalse(h.controller.state.value.isPlaying)
        assertEquals(0, h.controller.state.value.activeSectionIndex)
        assertEquals(90, h.controller.state.value.bpm)

        h.controller.setSongLoop(song2, true)
        h.controller.loadSetlist(h.controller.state.value.setlists.single())
        h.controller.start()
        seedBar(h.controller)
        repeat(4) { advanceBars(h.controller, 2) }
        assertTrue(h.controller.state.value.isPlaying)
        assertEquals(2, h.controller.state.value.activeSectionIndex)
        assertEquals(110, h.controller.state.value.bpm)

        h.controller.setSongLoop(song2, false)
        h.controller.setSetlistLoop(setlistId, true)
        h.controller.loadSetlist(h.controller.state.value.setlists.single())
        h.controller.start()
        seedBar(h.controller)
        repeat(4) { advanceBars(h.controller, 2) }
        assertTrue(h.controller.state.value.isPlaying)
        assertEquals(0, h.controller.state.value.activeSectionIndex)
        assertEquals(90, h.controller.state.value.bpm)
    }

    private fun sectionConfig(controller: MetronomeController, index: Int) =
        slots(controller)[index].section

    private fun slots(controller: MetronomeController): List<SetlistSlot> {
        val s = controller.state.value
        return s.setlistSlots(s.setlists.single())
    }

    private fun persistAndLoad(controller: MetronomeController, specs: List<SlotSpec>) {
        controller.createSetlist("Set")
        val id = controller.state.value.setlists.single().id
        specs.forEach { spec ->
            val song = controller.createSongFromCurrent(spec.name ?: "Song")!!
            val sectionId = song.sectionIds.single()
            applySpec(controller, spec, sectionId)
            controller.setSongSectionAutoAdvance(song.id, sectionId, spec.autoAdvance)
            controller.addSongToSetlist(id, song.id)
        }
        controller.loadSetlist(controller.state.value.setlists.single())
    }

    private fun createTwoSectionSong(
        controller: MetronomeController,
        name: String,
        firstBpm: Int,
        secondBpm: Int,
    ): String {
        val song = controller.createSong(name)!!
        controller.setBpm(firstBpm)
        controller.addSectionToSong(song.id)
        controller.setBpm(secondBpm)
        controller.addSectionToSong(song.id)
        val ids = controller.state.value.songs.first { it.id == song.id }.sectionIds
        controller.setSectionBpm(ids[0], firstBpm)
        controller.setSectionBpm(ids[1], secondBpm)
        controller.setSectionBars(ids[0], 2)
        controller.setSectionBars(ids[1], 2)
        controller.setSongSectionAutoAdvance(song.id, ids[0], true)
        controller.setSongSectionAutoAdvance(song.id, ids[1], true)
        return song.id
    }

    private fun applySpec(controller: MetronomeController, spec: SlotSpec, sectionId: String) {
        controller.setSectionLabel(sectionId, spec.name)
        controller.setSectionBpm(sectionId, spec.bpm)
        controller.setSectionTimeSignature(sectionId, spec.signature)
        controller.setSectionSubdivision(sectionId, spec.subdivision)
        controller.setSectionBars(sectionId, spec.bars)
    }

    private fun threeSectionSet(autoBars: Int = 0) = listOf(
        SlotSpec("A", 90, TimeSignature(4, 4), Subdivision.QUARTER, autoBars, autoBars > 0),
        SlotSpec("B", 120, TimeSignature(3, 4), Subdivision.EIGHTH, autoBars, autoBars > 0),
        SlotSpec("C", 150, TimeSignature(7, 8), Subdivision.TRIPLET, autoBars, autoBars > 0),
    )
}

private data class SlotSpec(
    val name: String?,
    val bpm: Int,
    val signature: TimeSignature,
    val subdivision: Subdivision,
    val bars: Int,
    val autoAdvance: Boolean,
) {
    fun toSection(id: String) = Section(
        id = id,
        name = name,
        bars = bars,
        bpm = bpm,
        beats = signature.beats,
        noteValue = signature.noteValue,
        subdivision = subdivision,
        toneId = MetronomeTone.DEFAULT.id,
        accentNote = AccentNote.DEFAULT,
        restNote = AccentNote.OFF,
        beatAccents = BeatAccent.defaultPattern(signature.beats, signature.noteValue),
        swing = SwingFeel.OFF,
        groupTempo = false,
        countInBars = 0,
        mutePlayBars = 1,
        muteSilentBars = 0,
    )
}

private fun sampleLibrarySection(id: String, name: String, bars: Int, bpm: Int) = Section(
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
        countInBars = 0,
        mutePlayBars = 1,
        muteSilentBars = 0,
    )

private class Harness(
    val prefs: MemoryPrefs,
    val database: MetromDatabase,
    val controller: MetronomeController,
)

private fun harness(): Harness {
    val prefs = MemoryPrefs()
    val database = openMetromDatabase(createTestSqlDriver())
    return Harness(
        prefs = prefs,
        database = database,
        controller = makeController(prefs, database),
    )
}

private fun reloadSlots(h: Harness): List<SetlistSlot> {
    val controller = makeController(h.prefs, h.database)
    val s = controller.state.value
    return s.setlistSlots(s.setlists.single())
}

private fun makeController(prefs: PrefsStore, database: MetromDatabase): MetronomeController {
    val cache = SampleToneCache(EmptyAssets())
    val engine = MetronomeEngine(
        sink = FakeSink(),
        clock = FakeClock(),
        latencyPad = ZeroPad(),
        sampleCache = cache,
    )
    return MetronomeController(
        prefs = prefs,
        haptics = NoHaptics(),
        sampleCache = cache,
        engine = engine,
        runner = FakeRunner(),
        micCapture = null,
        database = database,
    )
}

private fun controller(): MetronomeController = harness().controller

private fun seedBar(controller: MetronomeController, beatsPerBar: Int = 4) {
    for (i in 0 until beatsPerBar) {
        controller.handleBeat(BeatEvent(i, i, i == 0, 0L))
    }
}

private fun advanceBars(controller: MetronomeController, count: Int, beatsPerBar: Int = 4) {
    repeat(count) {
        controller.handleBeat(BeatEvent(0, 0, true, 0L))
        for (i in 1 until beatsPerBar) {
            controller.handleBeat(BeatEvent(i, i, false, 0L))
        }
    }
}

private class MemoryPrefs : PrefsStore {
    private val data = mutableMapOf<String, Any>()
    override fun getString(key: String): String? = data[key] as? String
    override fun putString(key: String, value: String) { data[key] = value }
    override fun getInt(key: String, default: Int): Int = data[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { data[key] = value }
    override fun getFloat(key: String, default: Float): Float = data[key] as? Float ?: default
    override fun putFloat(key: String, value: Float) { data[key] = value }
    override fun getBoolean(key: String, default: Boolean): Boolean = data[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { data[key] = value }
    override fun remove(key: String) { data.remove(key) }
    override fun contains(key: String): Boolean = data.containsKey(key)
}

private class FakeRunner : EngineRunner {
    private var running = false
    override fun start(engine: MetronomeEngine): Boolean { running = true; return true }
    override fun stop(engine: MetronomeEngine): Boolean { running = false; return true }
    override fun dispose(engine: MetronomeEngine) { running = false }
    override fun preview(engine: MetronomeEngine, accent: Boolean) {}
    override fun isRunning(engine: MetronomeEngine): Boolean = running
}

private class FakeSink : AudioSink {
    override fun start(sampleRate: Int, channelCount: Int, preferredBufferFrames: Int): Int = 64
    override fun write(pcm: ShortArray, offset: Int, count: Int): Int = count
    override fun playbackHeadFrames(): Long = 0L
    override fun stop() {}
    override fun dispose() {}
    override fun routeHint(): AudioRouteHint = AudioRouteHint.UNKNOWN
}

private class FakeClock : UiClock {
    override fun nowMs(): Long = 0L
    override fun postAt(uptimeMs: Long, block: () -> Unit) {}
    override fun cancelAll() {}
}

private class ZeroPad : LatencyPad {
    override fun padMs(route: AudioRouteHint, bufferHintMs: Int): Long = 0L
}

private class EmptyAssets : AssetIO {
    override fun open(path: String): ByteArray? = null
    override fun exists(path: String): Boolean = false
}

private class NoHaptics : Haptics {
    override fun beat(isAccent: Boolean) {}
}
