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
        val setlistId = h.controller.state.value.activeSetlistId!!
        val slots = slots(h.controller)
        val a = slots[0].section.id
        val b = slots[1].section.id
        h.controller.setSectionBpm(setlistId, b, 188)
        h.controller.setSectionTimeSignature(setlistId, b, TimeSignature(5, 4))
        h.controller.setSectionSubdivision(setlistId, b, Subdivision.SIXTEENTH)
        h.controller.setSectionSwing(setlistId, b, SwingFeel.MED)
        h.controller.setSectionTone(setlistId, b, MetronomeTone.Synth(ClickTone.BEEP))
        h.controller.setSectionAccentNote(setlistId, b, AccentNote.C4)
        h.controller.setSectionRestNote(setlistId, b, AccentNote.G4)
        h.controller.setSectionCountInBars(setlistId, b, 2)
        h.controller.setSectionLabel(setlistId, b, "Bridge")
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
        val setlistId = h.controller.state.value.activeSetlistId!!
        val sectionId = slots(h.controller)[0].section.id
        h.controller.setSectionBpm(setlistId, sectionId, 10)
        assertEquals(MetronomeLimits.MIN_BPM, sectionConfig(h.controller, 0).bpm)
        h.controller.setSectionBpm(setlistId, sectionId, 400)
        assertEquals(MetronomeLimits.MAX_BPM, sectionConfig(h.controller, 0).bpm)
        h.controller.setSectionBeatAccents(
            setlistId,
            sectionId,
            listOf(BeatAccent.STRONG, BeatAccent.MUTE),
        )
        assertEquals(4, sectionConfig(h.controller, 0).beatAccents.size)
        h.controller.setSectionBeatAccents(
            setlistId,
            sectionId,
            List(8) { BeatAccent.MUTE },
        )
        assertEquals(4, sectionConfig(h.controller, 0).beatAccents.size)
        assertTrue(sectionConfig(h.controller, 0).beatAccents.all { it == BeatAccent.MUTE })
        h.controller.setSectionGroupTempo(setlistId, sectionId, true)
        assertFalse(sectionConfig(h.controller, 0).groupTempo)
        h.controller.setSectionTimeSignature(setlistId, sectionId, TimeSignature(6, 8))
        h.controller.setSectionGroupTempo(setlistId, sectionId, true)
        assertTrue(sectionConfig(h.controller, 0).groupTempo)
        h.controller.setSectionTimeSignature(setlistId, sectionId, TimeSignature(4, 4))
        assertFalse(sectionConfig(h.controller, 0).groupTempo)
        assertEquals(4, sectionConfig(h.controller, 0).beatAccents.size)
        h.controller.setSectionCountInBars(setlistId, sectionId, 99)
        assertEquals(4, sectionConfig(h.controller, 0).countInBars)
        h.controller.setSectionLabel(setlistId, sectionId, "  ")
        assertEquals(null, slots(h.controller)[0].section.name)
    }

    @Test
    fun setSectionBarsAcceptsFreeNumbersAndClamps() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet())
        val setlistId = h.controller.state.value.activeSetlistId!!
        val sectionId = slots(h.controller)[0].section.id
        listOf(3, 7, 12, 24).forEach { bars ->
            h.controller.setSectionBars(setlistId, sectionId, bars)
            assertEquals(bars, slots(h.controller)[0].section.bars)
        }
        h.controller.setSectionBars(setlistId, sectionId, 0)
        assertEquals(0, slots(h.controller)[0].section.bars)
        h.controller.setSectionBars(setlistId, sectionId, -5)
        assertEquals(0, slots(h.controller)[0].section.bars)
        h.controller.setSectionBars(setlistId, sectionId, 5000)
        assertEquals(999, slots(h.controller)[0].section.bars)
        val reloaded = reloadSlots(h)[0]
        assertEquals(999, reloaded.section.bars)
    }

    @Test
    fun captureCurrentIntoSectionOverwritesConfigKeepsMeta() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet(autoBars = 8))
        val setlistId = h.controller.state.value.activeSetlistId!!
        val target = slots(h.controller)[1]
        assertEquals("B", target.section.name)
        assertEquals(8, target.section.bars)
        assertTrue(target.autoAdvance)
        h.controller.setBpm(111)
        h.controller.setSubdivision(Subdivision.SIXTEENTH)
        h.controller.setSwing(SwingFeel.HEAVY)
        h.controller.captureCurrentIntoSection(setlistId, target.section.id)
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
        val setlistId = h.controller.state.value.activeSetlistId!!
        val section0 = slots(h.controller)[0].section.id
        val section1 = slots(h.controller)[1].section.id
        assertEquals(90, h.controller.state.value.bpm)
        h.controller.setSectionBpm(setlistId, section0, 144)
        assertEquals(144, h.controller.state.value.bpm)
        h.controller.setSectionBpm(setlistId, section1, 160)
        assertEquals(144, h.controller.state.value.bpm)
        h.controller.start()
        assertEquals(144, h.controller.state.value.bpm)
        h.controller.setSectionBpm(setlistId, section0, 200)
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
    fun deleteSectionSucceedsAfterRemoveSectionUnlink() {
        val controller = controller()
        persistAndLoad(controller, listOf(threeSectionSet().first()))
        val setlistId = controller.state.value.setlists.single().id
        val section = slots(controller).single().section
        val blocked = controller.deleteSection(section)
        assertTrue(blocked is DeleteResult.Blocked)
        assertEquals(1, (blocked as DeleteResult.Blocked).usage.count)
        assertNotNull(controller.state.value.sections.firstOrNull { it.id == section.id })

        controller.removeSection(setlistId, section.id)
        assertTrue(controller.state.value.setlists.single().songIds.isEmpty())
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
    fun deleteSetlistCleansOrphanedWrapperSongsAndTheirUnreferencedSections() {
        val controller = controller()
        persistAndLoad(controller, listOf(threeSectionSet().first()))
        val setlistId = controller.state.value.setlists.single().id
        val songIds = controller.state.value.setlists.single().songIds
        val sectionIds = slots(controller).map { it.section.id }
        val cleaned = controller.deleteSetlist(setlistId)
        assertEquals(DeleteResult.Deleted, cleaned)
        assertTrue(controller.state.value.setlists.isEmpty())
        songIds.forEach { id -> assertTrue(controller.state.value.songs.none { it.id == id }) }
        sectionIds.forEach { id -> assertTrue(controller.state.value.sections.none { it.id == id }) }
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
            controller.addSectionFromCurrent(id)
            val added = slots(controller).last()
            controller.updateSection(id, spec.toSection(added.section.id))
            controller.setSectionAutoAdvance(id, added.section.id, spec.autoAdvance)
        }
        controller.loadSetlist(controller.state.value.setlists.single())
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
