package com.metrom.shared.practice

import com.metrom.shared.audio.SampleToneCache
import com.metrom.shared.data.SetSection
import com.metrom.shared.data.Setlist
import com.metrom.shared.data.SongPreset
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.BeatEvent
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

private fun controller(): MetronomeController {
    val cache = SampleToneCache(EmptyAssets())
    val engine = MetronomeEngine(
        sink = FakeSink(),
        clock = FakeClock(),
        latencyPad = ZeroPad(),
        sampleCache = cache,
    )
    return MetronomeController(
        prefs = MemoryPrefs(),
        haptics = NoHaptics(),
        sampleCache = cache,
        engine = engine,
        runner = FakeRunner(),
        micCapture = null,
    )
}

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
