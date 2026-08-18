package com.metrom.shared.garmin

import com.metrom.shared.domain.MetronomeLimits
import com.metrom.shared.practice.MetronomeUiState

/**
 * Phone ↔ Garmin Connect IQ dictionary protocol.
 *
 * Watch app id must match `garmin/manifest.xml`. Keys stay short because CIQ
 * BLE payloads are small and not beat-accurate — the watch runs its own visual clock.
 */
object GarminProtocol {
    const val APP_ID = "1eb9a92ec65945a0980cd4398e2399d5"

    fun snapshot(state: MetronomeUiState): Map<String, Any> = mapOf(
        "t" to "state",
        "bpm" to state.bpm,
        "play" to if (state.isPlaying) 1 else 0,
        "beats" to state.timeSignature.beats,
        "note" to state.timeSignature.noteValue,
    )

    sealed class Command {
        data object Sync : Command()
        data object Toggle : Command()
        data object Play : Command()
        data object Stop : Command()
        data object Tap : Command()
        data class Nudge(val delta: Int) : Command()
        data class SetBpm(val bpm: Int) : Command()
        data class Meter(val beats: Int, val note: Int) : Command()
    }

    fun parseCommand(data: Any?): Command? {
        val map = unwrap(data) ?: return null
        if (map["t"]?.toString() != "cmd") return null
        return when (map["a"]?.toString()) {
            "sync" -> Command.Sync
            "toggle" -> Command.Toggle
            "play" -> Command.Play
            "stop" -> Command.Stop
            "tap" -> Command.Tap
            "nudge" -> Command.Nudge(map["d"].asInt() ?: 1)
            "bpm" -> {
                val bpm = map["v"].asInt()?.coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM)
                    ?: return null
                Command.SetBpm(bpm)
            }
            "meter" -> {
                val beats = map["b"].asInt()?.coerceIn(1, 16) ?: return null
                val note = map["n"].asInt() ?: 4
                Command.Meter(beats, note)
            }
            else -> null
        }
    }

    private fun unwrap(data: Any?): Map<*, *>? = when (data) {
        is Map<*, *> -> data
        is List<*> -> data.firstOrNull() as? Map<*, *>
        else -> null
    }

    private fun Any?.asInt(): Int? = when (this) {
        is Int -> this
        is Long -> this.toInt()
        is Float -> this.toInt()
        is Double -> this.toInt()
        is Number -> this.toInt()
        else -> this?.toString()?.toIntOrNull()
    }
}
