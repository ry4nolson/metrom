package com.metrom.shared.theme

import com.metrom.shared.platform.PrefsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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

class ColorThemeTest {
    @Test
    fun defaultLoadIsEmber() {
        val store = ColorThemeStore(MemoryPrefs())
        assertEquals(ColorTheme.EMBER, store.load())
        assertEquals("ember", store.selectedId())
    }

    @Test
    fun selectPresetPersists() {
        val store = ColorThemeStore(MemoryPrefs())
        store.select("ice")
        assertEquals(ColorTheme.ICE, store.load())
        store.select("forest")
        assertEquals(ColorTheme.FOREST.id, store.load().id)
    }

    @Test
    fun unknownPresetFallsBackToEmber() {
        assertEquals(ColorTheme.EMBER, ColorTheme.preset("nope"))
    }

    @Test
    fun encodeRoundTrip() {
        val encoded = ColorTheme.encode(ColorTheme.ICE)
        val decoded = ColorTheme.decode(encoded)
        assertEquals(ColorTheme.ICE, decoded)
    }

    @Test
    fun decodeRejectsBadPayload() {
        assertNull(ColorTheme.decode(null))
        assertNull(ColorTheme.decode("too|few"))
        assertNull(ColorTheme.decode(ColorTheme.encode(ColorTheme.EMBER).replace("FF6A3D", "GGGGGG")))
    }

    @Test
    fun normalizeHex() {
        assertEquals("FF6A3D", ColorTheme.normalizeHex("#ff6a3d"))
        assertNull(ColorTheme.normalizeHex("fff"))
        assertNull(ColorTheme.normalizeHex("red"))
    }

    @Test
    fun customCopyAndSlotUpdate() {
        val store = ColorThemeStore(MemoryPrefs())
        store.select("forest")
        store.updateSlot(ColorSlots.EMBER, "#00FF00")
        val custom = store.load()
        assertEquals(ColorTheme.CUSTOM_ID, custom.id)
        assertEquals("00FF00", custom.ember)
        assertEquals(ColorTheme.FOREST.pulse, custom.pulse)
        store.select("ember")
        assertEquals(ColorTheme.EMBER, store.load())
        store.select(ColorTheme.CUSTOM_ID)
        assertEquals("00FF00", store.load().ember)
    }

    @Test
    fun withHexIgnoresInvalid() {
        val next = ColorTheme.EMBER.withHex(ColorSlots.PULSE, "nope")
        assertEquals(ColorTheme.EMBER, next)
        assertNotEquals(ColorTheme.EMBER.ember, ColorTheme.ICE.ember)
        assertTrue(ColorSlots.ALL.isNotEmpty())
    }

    @Test
    fun presetsMixLightAndTintedDarkStages() {
        assertTrue(ColorTheme.PAPER.isLight())
        assertTrue(ColorTheme.SNOW.isLight())
        assertTrue(ColorTheme.BLOOM.isLight())
        assertTrue(!ColorTheme.EMBER.isLight())
        assertTrue(!ColorTheme.ICE.isLight())
        val darkInks = ColorTheme.PRESETS.filter { !it.isLight() }.map { it.ink }.toSet()
        assertTrue(darkInks.size >= 6)
        assertTrue(ColorTheme.PRESETS.any { it.isLight() })
        assertEquals(ColorTheme.PAPER, ColorTheme.preset("paper"))
    }

    @Test
    fun saveNamedThemePersistsAndOverwritesLabel() {
        val store = ColorThemeStore(MemoryPrefs())
        store.select("ice")
        val first = store.saveNamed("  Cool ice  ", store.load())
        assertTrue(first.isSaved())
        assertEquals("Cool ice", first.label)
        assertEquals(ColorTheme.ICE.ember, store.load().ember)
        assertEquals(1, store.saved().size)
        store.saveNamed("Cool ice", ColorTheme.FOREST)
        assertEquals(1, store.saved().size)
        assertEquals(ColorTheme.FOREST.ember, store.load().ember)
        store.select("ember")
        store.select(first.id)
        assertEquals(ColorTheme.FOREST.ember, store.load().ember)
    }

    @Test
    fun deleteSavedFallsBackToCustom() {
        val store = ColorThemeStore(MemoryPrefs())
        store.saveCustom(ColorTheme.FOREST)
        val saved = store.saveNamed("Keep", ColorTheme.BLOOM)
        store.deleteSaved(saved.id)
        assertTrue(store.saved().isEmpty())
        assertEquals(ColorTheme.CUSTOM_ID, store.selectedId())
        assertEquals(ColorTheme.FOREST.ember, store.load().ember)
    }

    @Test
    fun previewSwatchesCoverAccentsAndStage() {
        assertEquals(5, ColorTheme.EMBER.accentSwatches().size)
        assertEquals(2, ColorTheme.EMBER.stageSwatches().size)
        assertEquals(ColorTheme.EMBER.ink, ColorTheme.EMBER.stageSwatches()[0])
    }
}
