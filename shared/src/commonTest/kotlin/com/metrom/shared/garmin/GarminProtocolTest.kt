package com.metrom.shared.garmin

import com.metrom.shared.practice.MetronomeUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GarminProtocolTest {
    @Test
    fun parseToggle() {
        val cmd = GarminProtocol.parseCommand(mapOf("t" to "cmd", "a" to "toggle"))
        assertIs<GarminProtocol.Command.Toggle>(cmd)
    }

    @Test
    fun parseNudgeFromListWrapper() {
        val cmd = GarminProtocol.parseCommand(listOf(mapOf("t" to "cmd", "a" to "nudge", "d" to 5L)))
        assertEquals(GarminProtocol.Command.Nudge(5), cmd)
    }

    @Test
    fun parseMeter() {
        val cmd = GarminProtocol.parseCommand(mapOf("t" to "cmd", "a" to "meter", "b" to 6, "n" to 8))
        assertEquals(GarminProtocol.Command.Meter(6, 8), cmd)
    }

    @Test
    fun ignoreUnknown() {
        assertNull(GarminProtocol.parseCommand(mapOf("t" to "cmd", "a" to "nope")))
        assertNull(GarminProtocol.parseCommand("toggle"))
    }

    @Test
    fun snapshotOmitsPerBeatFields() {
        val snap = GarminProtocol.snapshot(MetronomeUiState(bpm = 128, isPlaying = true))
        assertEquals(setOf("t", "bpm", "play", "beats", "note"), snap.keys)
        assertEquals(128, snap["bpm"])
        assertEquals(1, snap["play"])
    }
}
