package com.metrom.app.platform

import android.os.Process
import com.metrom.shared.engine.MetronomeEngine
import com.metrom.shared.platform.EngineRunner
import java.util.concurrent.atomic.AtomicInteger

class AndroidEngineRunner(
    private val sink: AndroidAudioSink,
) : EngineRunner {
    private val lifecycleLock = Any()
    private val previewGeneration = AtomicInteger(0)

    @Volatile private var audioThread: Thread? = null

    override fun start(engine: MetronomeEngine) {
        synchronized(lifecycleLock) {
            if (engine.playing && audioThread?.isAlive == true) return
            stopLocked(engine)
            engine.markPlaying()
            val thread = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    engine.runLoop()
                } finally {
                    // After runLoop returns, this thread may release. Main-thread
                    // stopLocked may also release after join. AndroidAudioSink.dispose
                    // uses AtomicReference.getAndSet — exactly one caller wins.
                    engine.markStopped()
                    try {
                        sink.dispose()
                    } catch (_: Exception) {
                    }
                }
            }, "metrom-audio")
            audioThread = thread
            thread.start()
        }
    }

    override fun stop(engine: MetronomeEngine) {
        synchronized(lifecycleLock) {
            stopLocked(engine)
        }
    }

    override fun dispose(engine: MetronomeEngine) {
        synchronized(lifecycleLock) {
            previewGeneration.incrementAndGet()
            stopLocked(engine)
            sink.dispose()
        }
    }

    override fun preview(engine: MetronomeEngine, accent: Boolean) {
        val gen = previewGeneration.incrementAndGet()
        Thread({
            if (gen != previewGeneration.get()) return@Thread
            val pcm = engine.resolvePreviewPcm(accent)
            sink.previewStatic(pcm, volume = 0.9f)
        }, "metrom-preview").start()
    }

    override fun isRunning(engine: MetronomeEngine): Boolean =
        engine.playing && audioThread?.isAlive == true

    /**
     * Teardown order: mark stopped → unblock write → join audio thread → release track.
     * Join stays bounded at the existing 1500ms (+ one retry).
     * Release is safe vs the audio-thread finally via AtomicReference.getAndSet in the sink.
     */
    private fun stopLocked(engine: MetronomeEngine) {
        engine.markStopped()
        try {
            sink.stop()
        } catch (_: Exception) {
        }
        val thread = audioThread
        if (thread != null) {
            try {
                thread.join(1_500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                try {
                    thread.join(1_500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (audioThread === thread) audioThread = null
        }
        try {
            sink.dispose()
        } catch (_: Exception) {
        }
    }
}
