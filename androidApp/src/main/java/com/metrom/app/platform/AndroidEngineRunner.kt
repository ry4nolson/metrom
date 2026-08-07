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
    private val sessionGeneration = AtomicInteger(0)

    /**
     * Invoked only when a session ends because the engine failed (start threw, write
     * error), not when the user requested stop. Never invoked for a newer session
     * than the one that failed (generation check at the source).
     */
    @Volatile var onSessionEnded: (() -> Unit)? = null

    @Volatile private var audioThread: Thread? = null

    /** Non-null while a prior join timed out and that thread has not exited yet. */
    @Volatile private var wedgedThread: Thread? = null

    /**
     * Session generation for which user/stopLocked requested stop.
     * Compared in the audio-thread finally so [onSessionEnded] fires only for
     * engine failures, not user-initiated teardown.
     */
    @Volatile private var userStopGeneration: Int = -1

    override fun start(engine: MetronomeEngine) {
        synchronized(lifecycleLock) {
            if (engine.playing && audioThread?.isAlive == true) return

            val wedged = wedgedThread
            if (wedged != null) {
                if (wedged.isAlive) {
                    // Fail fast: do not re-join a known-wedged thread (would freeze main ~3s).
                    Log.e(
                        AndroidAudioSink.TAG,
                        "refusing start: wedged audio thread still alive (fail fast, no join)",
                    )
                    return
                }
                Log.i(AndroidAudioSink.TAG, "wedged audio thread exited; clearing wedge")
                wedgedThread = null
                if (audioThread === wedged) audioThread = null
            }

            if (!stopLocked(engine)) {
                Log.e(
                    AndroidAudioSink.TAG,
                    "refusing start: join timed out; thread marked wedged",
                )
                return
            }

            engine.markPlaying()
            val generation = sessionGeneration.incrementAndGet()
            val sessionEngine = engine
            val thread = Thread({
                val self = Thread.currentThread()
                var engineFailed = false
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    try {
                        sessionEngine.runLoop()
                    } catch (t: Throwable) {
                        engineFailed = true
                        Log.e(AndroidAudioSink.TAG, "audio thread failed", t)
                    }
                } finally {
                    teardownAudioThread(sessionEngine, self, generation, engineFailed)
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
     * Finally / ownership guard for a session thread.
     *
     * ```
     * stillCurrent = (audioThread === self)
     * wasWedged    = (wedgedThread === self)
     * userStopped  = (userStopGeneration == generation)
     * if (wasWedged) wedgedThread = null          // recover
     * if (stillCurrent) { markStopped; dispose; audioThread = null }
     * notify = !userStopped && sessionGeneration == generation && (engineFailed || stillCurrent)
     * ```
     *
     * [onSessionEnded] is an engine-failure event only. User stop sets
     * [userStopGeneration] in [stopLocked] before join, so the finally suppresses it.
     * Generation equality blocks notify against a newer session.
     */
    private fun teardownAudioThread(
        sessionEngine: MetronomeEngine,
        self: Thread,
        generation: Int,
        engineFailed: Boolean,
    ) {
        val userStopped = userStopGeneration == generation
        val stillCurrent = audioThread === self
        val wasWedged = wedgedThread === self

        if (wasWedged) {
            wedgedThread = null
            Log.i(AndroidAudioSink.TAG, "wedged audio thread exited; wedge cleared")
        }

        if (stillCurrent) {
            sessionEngine.markStopped()
            try {
                sink.dispose()
            } catch (_: Exception) {
            }
            if (audioThread === self) audioThread = null
        } else if (!wasWedged) {
            Log.w(
                AndroidAudioSink.TAG,
                "audio thread exiting but not current; skipping sink dispose",
            )
        }

        // Engine failure / unexpected exit only — not user-requested stop.
        // stillCurrent was sampled before we nulled audioThread above.
        val notifyFailure =
            !userStopped &&
                sessionGeneration.get() == generation &&
                (engineFailed || stillCurrent)

        if (notifyFailure) {
            try {
                onSessionEnded?.invoke()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Teardown order: mark stopped → unblock write → join → release (if joined).
     * Join stays bounded (1500ms + one retry).
     *
     * @return false if the audio thread is still alive after the bounded join.
     */
    private fun stopLocked(engine: MetronomeEngine): Boolean {
        val gen = sessionGeneration.get()
        userStopGeneration = gen
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
        // Already wedged: do not spend another 3s on main.
        if (wedgedThread === thread && thread.isAlive) {
            Log.e(AndroidAudioSink.TAG, "stopLocked: thread already wedged; skip re-join")
            return false
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
            wedgedThread = thread
            Log.e(
                AndroidAudioSink.TAG,
                "audio join timed out; thread marked wedged (no main-thread release)",
            )
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
