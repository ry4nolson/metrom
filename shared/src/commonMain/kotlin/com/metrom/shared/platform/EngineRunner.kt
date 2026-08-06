package com.metrom.shared.platform

import com.metrom.shared.engine.MetronomeEngine

/** Platform owns the audio thread and preview playback. */
interface EngineRunner {
    fun start(engine: MetronomeEngine)
    fun stop(engine: MetronomeEngine)
    fun dispose(engine: MetronomeEngine)
    fun preview(engine: MetronomeEngine, accent: Boolean)
    fun isRunning(engine: MetronomeEngine): Boolean
}
