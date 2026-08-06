package com.metrom.app.platform

import android.os.Process
import android.util.Log
import com.metrom.shared.engine.MetronomeEngine
import com.metrom.shared.platform.EngineRunner
import java.util.concurrent.atomic.AtomicInteger

class AndroidEngineRunner(
    private val sink: AndroidAudioSink,
) : EngineRunner {
    private val lifecycleLock = Any()
    private val previewGeneration = AtomicInteger(0)

    /** Set by the ViewModel to sync UI when the audio thread ends without a normal stop. */
    @Volatile var onSessionEnded: (() -> Unit)? = null

    @Volatile private var audioThread: Thread? = null

    override fun start(engine: MetronomeEngine) {
        synchronized(lifecycleLock) {
            if (engine.playing && audioThread?.isAlive == true) return
            if (!stopLocked(engine)) {
                // Join timed out: zombie still owns the session. Do not spawn over it.
                Log.e(
                    AndroidAudioSink.TAG,
                    "refusing start: previous audio thread still alive after join timeout",
                )
                return
            }
            engine.markPlaying()
            val thread = Thread({
                val self = Thread.currentThread()
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    try {
                        engine.runLoop()
                    } catch (t: Throwable) {
                        // Includes failures from sink.start() before runLoop's try.
                        Log.e(AndroidAudioSink.TAG, "audio thread failed", t)
                    }
                } finally {
                    // Only the current session thread may tear down. A timed-out
                    // predecessor must not markStopped/dispose a newer session.
                    if (audioThread !== self) {
                        Log.w(
                            AndroidAudioSink.TAG,
                            "stale audio thread exiting; skipping teardown",
                        )
                        return@Thread
                    }
                    engine.markStopped()
                    try {
                        sink.dispose()
                    } catch (_: Exception) {
                    }
                    if (audioThread === self) audioThread = null
                    try {
                        onSessionEnded?.invoke()
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
     * Join stays bounded (1500ms + one retry).
     *
     * @return false if the audio thread is still alive after the bounded join.
     *         Caller must not spawn a replacement session. The live thread remains
     *         [audioThread] so its finally can dispose when it eventually exits;
     *         main does not release while write may still be in flight.
     */
    private fun stopLocked(engine: MetronomeEngine): Boolean {
        engine.markStopped()
        try {
            sink.stop()
        } catch (_: Exception) {
        }
        val thread = audioThread
        if (thread == null) {
            try {
                sink.dispose()
            } catch (_: Exception) {
            }
            return true
        }
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
        if (thread.isAlive) {
            Log.e(
                AndroidAudioSink.TAG,
                "audio join timed out; thread left as owner until it exits (no main-thread release)",
            )
            // Keep audioThread == thread so its finally is not "stale".
            return false
        }
        if (audioThread === thread) audioThread = null
        try {
            sink.dispose()
        } catch (_: Exception) {
        }
        return true
    }
}
