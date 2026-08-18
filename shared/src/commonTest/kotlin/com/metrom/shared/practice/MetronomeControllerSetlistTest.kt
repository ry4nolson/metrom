package com.metrom.shared.practice

import com.metrom.shared.audio.SampleToneCache
import com.metrom.shared.data.SetSection
import com.metrom.shared.data.Setlist
import com.metrom.shared.data.SongPreset
import com.metrom.shared.db.MetromDatabase
import com.metrom.shared.db.openMetromDatabase
import com.metrom.shared.library.createTestSqlDriver
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.BeatEvent
import com.metrom.shared.domain.ClickTone
import com.metrom.shared.domain.MetronomeLimits
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.MutePattern
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
import kotlin.test.assertTrue

class MetronomeControllerSetlistTest {
    @Test
    fun nonLoopingSetEndStopsAndRearmsSectionZero() {
        val controller = controller()
        val setlist = threeSectionSet(loop = false, autoBars = 2)
        persistAndLoad(controller, setlist)
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
        val sections = h.controller.state.value.setlists.single().sections
        val a = sections[0].id
        val b = sections[1].id
        h.controller.setSectionBpm(setlistId, b, 188)
        h.controller.setSectionTimeSignature(setlistId, b, TimeSignature(5, 4))
        h.controller.setSectionSubdivision(setlistId, b, Subdivision.SIXTEENTH)
        h.controller.setSectionSwing(setlistId, b, SwingFeel.MED)
        h.controller.setSectionTone(setlistId, b, MetronomeTone.Synth(ClickTone.BEEP))
        h.controller.setSectionAccentNote(setlistId, b, AccentNote.C4)
        h.controller.setSectionRestNote(setlistId, b, AccentNote.G4)
        h.controller.setSectionCountInBars(setlistId, b, 2)
        h.controller.setSectionLabel(setlistId, b, "Bridge")
        val stored = h.controller.state.value.setlists.single().sections
        assertEquals(90, stored[0].config.bpm)
        assertEquals(188, stored[1].config.bpm)
        assertEquals(TimeSignature(5, 4), stored[1].config.timeSignature)
        assertEquals(Subdivision.SIXTEENTH, stored[1].config.subdivision)
        assertEquals(SwingFeel.MED, stored[1].config.swing)
        assertEquals(MetronomeTone.Synth(ClickTone.BEEP), stored[1].config.tone)
        assertEquals(AccentNote.C4, stored[1].config.accentNote)
        assertEquals(AccentNote.G4, stored[1].config.restNote)
        assertEquals(2, stored[1].config.countInBars)
        assertEquals("Bridge", stored[1].label)
        assertEquals(a, stored[0].id)
        val reloaded = reloadSetlist(h)
        assertEquals(188, reloaded.sections[1].config.bpm)
        assertEquals(TimeSignature(5, 4), reloaded.sections[1].config.timeSignature)
        assertEquals("Bridge", reloaded.sections[1].label)
        assertEquals(90, reloaded.sections[0].config.bpm)
    }

    @Test
    fun sectionConfigValidationMatchesTopLevelSetters() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet())
        val setlistId = h.controller.state.value.activeSetlistId!!
        val sectionId = h.controller.state.value.setlists.single().sections[0].id
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
        assertEquals(null, h.controller.state.value.setlists.single().sections[0].label)
    }

    @Test
    fun setSectionBarsAcceptsFreeNumbersAndClamps() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet())
        val setlistId = h.controller.state.value.activeSetlistId!!
        val sectionId = h.controller.state.value.setlists.single().sections[0].id
        listOf(3, 7, 12, 24).forEach { bars ->
            h.controller.setSectionBars(setlistId, sectionId, bars)
            assertEquals(bars, h.controller.state.value.setlists.single().sections[0].bars)
        }
        h.controller.setSectionBars(setlistId, sectionId, 0)
        assertEquals(0, h.controller.state.value.setlists.single().sections[0].bars)
        h.controller.setSectionBars(setlistId, sectionId, -5)
        assertEquals(0, h.controller.state.value.setlists.single().sections[0].bars)
        h.controller.setSectionBars(setlistId, sectionId, 5000)
        assertEquals(999, h.controller.state.value.setlists.single().sections[0].bars)
        val reloaded = reloadSetlist(h).sections[0]
        assertEquals(999, reloaded.bars)
    }

    @Test
    fun captureCurrentIntoSectionOverwritesConfigKeepsMeta() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet(autoBars = 8))
        val setlistId = h.controller.state.value.activeSetlistId!!
        val target = h.controller.state.value.setlists.single().sections[1]
        assertEquals("B", target.label)
        assertEquals(8, target.bars)
        assertTrue(target.autoAdvance)
        h.controller.setBpm(111)
        h.controller.setSubdivision(Subdivision.SIXTEENTH)
        h.controller.setSwing(SwingFeel.HEAVY)
        h.controller.captureCurrentIntoSection(setlistId, target.id)
        val updated = h.controller.state.value.setlists.single().sections[1]
        assertEquals(111, updated.config.bpm)
        assertEquals(Subdivision.SIXTEENTH, updated.config.subdivision)
        assertEquals(SwingFeel.HEAVY, updated.config.swing)
        assertEquals(8, updated.bars)
        assertTrue(updated.autoAdvance)
        assertEquals("B", updated.label)
        assertEquals(90, h.controller.state.value.setlists.single().sections[0].config.bpm)
    }

    @Test
    fun editWhileLoadedStoppedReappliesPlayingDoesNotHotSwap() {
        val h = harness()
        persistAndLoad(h.controller, threeSectionSet())
        val setlistId = h.controller.state.value.activeSetlistId!!
        val section0 = h.controller.state.value.setlists.single().sections[0].id
        val section1 = h.controller.state.value.setlists.single().sections[1].id
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

    private fun sectionConfig(controller: MetronomeController, index: Int) =
        controller.state.value.setlists.single().sections[index].config

    private fun persistAndLoad(controller: MetronomeController, setlist: Setlist) {
        controller.createSetlist(setlist.name)
        val id = controller.state.value.setlists.single().id
        setlist.sections.forEach { section ->
            controller.addSectionFromCurrent(id)
            val added = controller.state.value.setlists.single().sections.last()
            controller.updateSection(id, added.copy(label = section.label, config = section.config, bars = section.bars, autoAdvance = section.autoAdvance))
        }
        val stored = controller.state.value.setlists.single()
        controller.loadSetlist(stored)
    }

    private fun threeSectionSet(loop: Boolean = false, autoBars: Int = 0) = Setlist(
        name = "Set",
        loop = loop,
        sections = listOf(
            SetSection(
                label = "A",
                config = preset(90, TimeSignature(4, 4), Subdivision.QUARTER),
                bars = autoBars,
                autoAdvance = autoBars > 0,
            ),
            SetSection(
                label = "B",
                config = preset(120, TimeSignature(3, 4), Subdivision.EIGHTH),
                bars = autoBars,
                autoAdvance = autoBars > 0,
            ),
            SetSection(
                label = "C",
                config = preset(150, TimeSignature(7, 8), Subdivision.TRIPLET),
                bars = autoBars,
                autoAdvance = autoBars > 0,
            ),
        ),
    )

    private fun preset(bpm: Int, signature: TimeSignature, subdivision: Subdivision) = SongPreset(
        name = "$bpm",
        bpm = bpm,
        timeSignature = signature,
        subdivision = subdivision,
        tone = MetronomeTone.DEFAULT,
        accentNote = AccentNote.DEFAULT,
        restNote = AccentNote.OFF,
        beatAccents = BeatAccent.defaultPattern(signature.beats, signature.noteValue),
        swing = SwingFeel.OFF,
        groupTempo = false,
        countInBars = 0,
        mutePattern = MutePattern.OFF,
    )
}

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

private fun reloadSetlist(h: Harness): Setlist =
    makeController(h.prefs, h.database).state.value.setlists.single()

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
