package com.metrom.shared.data

import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.MutePattern
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.platform.PrefsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

class SetlistStoreTest {
    @Test
    fun roundTripThreeSectionsIncludingOpenEndedAndLoop() {
        val prefs = MemoryPrefs()
        val store = SetlistStore(prefs)
        val original = Setlist(
            name = "Practice set",
            loop = true,
            sections = listOf(
                SetSection(
                    label = "Intro",
                    config = preset("Intro 4/4", TimeSignature(4, 4), 96, Subdivision.QUARTER),
                    bars = 8,
                    autoAdvance = true,
                ),
                SetSection(
                    label = "Odd meter",
                    config = preset("Bridge 7/8", TimeSignature(7, 8), 112, Subdivision.EIGHTH),
                    bars = 0,
                    autoAdvance = false,
                ),
                SetSection(
                    label = null,
                    config = preset("Outro 6/8", TimeSignature(6, 8), 80, Subdivision.TRIPLET),
                    bars = 4,
                    autoAdvance = true,
                ),
            ),
        )
        store.saveAll(listOf(original))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(original, loaded.single())
        assertEquals(7, loaded.single().sections[1].config.timeSignature.beats)
        assertEquals(8, loaded.single().sections[1].config.timeSignature.noteValue)
        assertEquals(0, loaded.single().sections[1].bars)
        assertTrue(loaded.single().loop)
    }

    @Test
    fun corruptPayloadClearsKeyAndReturnsEmpty() {
        val prefs = MemoryPrefs()
        prefs.putString("setlists_json", "{not-json")
        val loaded = SetlistStore(prefs).load()
        assertEquals(emptyList(), loaded)
        assertFalse(prefs.contains("setlists_json"))
    }

    private fun preset(
        name: String,
        signature: TimeSignature,
        bpm: Int,
        subdivision: Subdivision,
    ) = SongPreset(
        name = name,
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
