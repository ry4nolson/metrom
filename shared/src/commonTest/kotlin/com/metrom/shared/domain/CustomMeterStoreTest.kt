package com.metrom.shared.domain

import com.metrom.shared.platform.PrefsStore
import kotlin.test.Test
import kotlin.test.assertEquals
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

class CustomMeterStoreTest {
    @Test
    fun parseRejectsInvalid() {
        assertNull(TimeSignature.parse("7"))
        assertNull(TimeSignature.parse("0/4"))
        assertNull(TimeSignature.parse("7/3"))
        assertEquals(TimeSignature(7, 4), TimeSignature.parse("7/4"))
        assertEquals(TimeSignature(11, 8), TimeSignature.parse(" 11/8 "))
    }

    @Test
    fun addSkipsCommonAndPersistsOddMeters() {
        val store = CustomMeterStore(MemoryPrefs())
        assertEquals(TimeSignature(4, 4), store.add(4, 4))
        assertTrue(store.all().isEmpty())
        val added = store.add(7, 4)
        assertEquals(TimeSignature(7, 4), added)
        assertEquals(listOf(TimeSignature(7, 4)), store.all())
        store.add(7, 4)
        store.add(11, 16)
        assertEquals(
            listOf(TimeSignature(7, 4), TimeSignature(11, 16)),
            store.all(),
        )
        store.remove(TimeSignature(7, 4))
        assertEquals(listOf(TimeSignature(11, 16)), store.all())
    }
}
