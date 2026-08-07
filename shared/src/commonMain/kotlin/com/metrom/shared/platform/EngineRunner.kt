package com.metrom.shared.platform

import com.metrom.shared.engine.MetronomeEngine

/** Platform owns the audio thread and preview playback. */
interface EngineRunner {
    /**
     * Begin a session. Returns true if the session was accepted (thread spawned
     * or already running). Returns false if rejected (e.g. wedged / stopping).
     */
    fun start(engine: MetronomeEngine): Boolean

    /**
     * Request stop. Returns true if teardown completed cleanly (idle).
     * Returns false if the audio thread did not join in time (wedged) —
     * audio may still be producing; do not treat as a clean stop.
     */
    fun stop(engine: MetronomeEngine): Boolean

    fun dispose(engine: MetronomeEngine)
    fun preview(engine: MetronomeEngine, accent: Boolean)
    fun isRunning(engine: MetronomeEngine): Boolean
}
