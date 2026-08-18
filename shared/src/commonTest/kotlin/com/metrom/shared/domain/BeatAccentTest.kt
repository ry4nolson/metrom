package com.metrom.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeatAccentTest {
    @Test
    fun simpleMeterAccentsDownbeatOnly() {
        assertEquals(
            listOf(BeatAccent.STRONG, BeatAccent.NORMAL, BeatAccent.NORMAL, BeatAccent.NORMAL),
            BeatAccent.defaultPattern(4, 4),
        )
    }

    @Test
    fun compoundMeterAccentsDownbeatOnly() {
        assertEquals(1, BeatAccent.defaultPattern(6, 8).count { it == BeatAccent.STRONG })
        assertEquals(1, BeatAccent.defaultPattern(9, 8).count { it == BeatAccent.STRONG })
        assertEquals(1, BeatAccent.defaultPattern(12, 8).count { it == BeatAccent.STRONG })
        assertEquals(BeatAccent.STRONG, BeatAccent.defaultPattern(6, 8).first())
    }

    @Test
    fun decodeMigratesLegacyCompoundDefault() {
        assertEquals(BeatAccent.defaultPattern(6, 8), BeatAccent.decode("SNNSNN", 6, 8))
        assertEquals(BeatAccent.defaultPattern(9, 8), BeatAccent.decode("SNNSNNSNN", 9, 8))
        assertEquals(BeatAccent.defaultPattern(12, 8), BeatAccent.decode("SNNSNNSNNSNN", 12, 8))
    }

    @Test
    fun decodeKeepsCustomCompoundPattern() {
        val custom = BeatAccent.decode("SMNSNN", 6, 8)
        assertEquals(BeatAccent.MUTE, custom[1])
        assertFalse(BeatAccent.isDefault(custom, 6, 8))
    }

    @Test
    fun defaultIsNotCustomized() {
        assertTrue(BeatAccent.isDefault(BeatAccent.defaultPattern(9, 8), 9, 8))
    }
}
