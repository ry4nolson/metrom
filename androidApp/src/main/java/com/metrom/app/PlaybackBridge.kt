package com.metrom.app

/**
 * Thin bridge so the foreground notification can toggle / stop playback
 * without owning the engine (engine lives in [MetronomeViewModel]).
 */
object PlaybackBridge {
    @Volatile
    var bpm: Int = 120

    @Volatile
    var playing: Boolean = false

    @Volatile
    var subtitle: String = "Metrom"

    @Volatile
    var onToggle: (() -> Unit)? = null

    /** Hard stop from the notification (clears transient-focus resume). */
    @Volatile
    var onStop: (() -> Unit)? = null
}
