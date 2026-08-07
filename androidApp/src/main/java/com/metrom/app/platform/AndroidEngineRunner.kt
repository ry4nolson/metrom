package com.metrom.app.platform

import android.os.Process
import android.util.Log
import com.metrom.shared.engine.MetronomeEngine
import com.metrom.shared.platform.EngineRunner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit session lifecycle. Single [state]; all changes via [transition].
 * Teardown: flag down → unblock write → join → release.
 */
class AndroidEngineRunner(
    private val sink: AndroidAudioSink,
) : EngineRunner {

    private enum class SessionState {
        IDLE, STARTING, RUNNING, STOPPING, WEDGED, FAILED
    }

    private val lock = Any()
    @Volatile private var state = SessionState.IDLE
    @Volatile private var audioThread: Thread? = null
    @Volatile var onEngineFailed: (() -> Unit)? = null
    @Volatile var onWedgeCleared: (() -> Unit)? = null

    private val previewCancel = AtomicBoolean(false)
    @Volatile private var previewThread: Thread? = null

    init {
        sink.onTrackStarted = {
            synchronized(lock) {
                if (state == SessionState.STARTING) transition(SessionState.RUNNING, "track started")
            }
        }
    }

    override fun start(engine: MetronomeEngine): Boolean = synchronized(lock) {
        when (state) {
            SessionState.RUNNING, SessionState.STARTING -> return true
            SessionState.WEDGED, SessionState.STOPPING -> {
                Log.e(TAG, "start rejected: state=$state")
                return false
            }
            SessionState.IDLE, SessionState.FAILED -> Unit
        }
        if (!transition(SessionState.STARTING, "play")) return false
        engine.markPlaying()
        val sessionEngine = engine
        val thread = Thread({
            val self = Thread.currentThread()
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                try {
                    sessionEngine.runLoop()
                } catch (t: Throwable) {
                    Log.e(TAG, "audio thread failed", t)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "audio thread outer failure", t)
            } finally {
                onAudioThreadExit(self)
            }
        }, "metrom-audio")
        audioThread = thread
        thread.start()
        true
    }

    override fun stop(engine: MetronomeEngine): Boolean {
        val thread = synchronized(lock) {
            when (state) {
                SessionState.IDLE, SessionState.FAILED -> {
                    engine.markStopped()
                    safeDispose()
                    transition(SessionState.IDLE, "stop noop")
                    return true
                }
                SessionState.WEDGED -> {
                    engine.markStopped()
                    try {
                        sink.stop()
                    } catch (_: Exception) {
                    }
                    Log.e(TAG, "stop while WEDGED")
                    return false
                }
                SessionState.STOPPING -> {
                    Log.w(TAG, "stop while already STOPPING")
                    audioThread
                }
                SessionState.STARTING, SessionState.RUNNING -> {
                    transition(SessionState.STOPPING, "user stop")
                    engine.markStopped()
                    try {
                        sink.stop()
                    } catch (_: Exception) {
                    }
                    audioThread
                }
            }
        }
        return joinAndFinish(thread)
    }

    override fun dispose(engine: MetronomeEngine) {
        previewCancel.set(true)
        joinQuiet(previewThread, 500L)
        stop(engine)
        synchronized(lock) {
            safeDispose()
            if (state != SessionState.WEDGED) transition(SessionState.IDLE, "dispose")
        }
    }

    override fun preview(engine: MetronomeEngine, accent: Boolean) {
        previewCancel.set(true)
        joinQuiet(previewThread, 100L)
        previewCancel.set(false)
        val pcm = engine.resolvePreviewPcm(accent)
        val thread = Thread(
            { sink.previewStatic(pcm, 0.9f, previewCancel) },
            "metrom-preview",
        )
        previewThread = thread
        thread.start()
    }

    override fun isRunning(engine: MetronomeEngine): Boolean =
        synchronized(lock) { state == SessionState.STARTING || state == SessionState.RUNNING }

    private fun onAudioThreadExit(self: Thread) {
        safeDispose()
        var notifyFailed = false
        var notifyCleared = false
        synchronized(lock) {
            if (audioThread === self) audioThread = null
            when (state) {
                SessionState.STOPPING -> Unit
                SessionState.WEDGED -> {
                    transition(SessionState.IDLE, "zombie exited")
                    notifyCleared = true
                }
                SessionState.STARTING, SessionState.RUNNING -> {
                    transition(SessionState.FAILED, "engine end")
                    notifyFailed = true
                }
                else -> Unit
            }
        }
        if (notifyFailed) {
            try {
                onEngineFailed?.invoke()
            } catch (_: Exception) {
            }
        }
        if (notifyCleared) {
            try {
                onWedgeCleared?.invoke()
            } catch (_: Exception) {
            }
        }
    }

    private fun joinAndFinish(thread: Thread?): Boolean {
        joinQuiet(thread, 1_500L)
        if (thread != null && thread.isAlive) joinQuiet(thread, 1_500L)
        synchronized(lock) {
            if (thread != null && thread.isAlive) {
                transition(SessionState.WEDGED, "join timeout")
                return false
            }
            safeDispose()
            when (state) {
                SessionState.STOPPING -> transition(SessionState.IDLE, "join ok")
                SessionState.FAILED, SessionState.IDLE -> Unit
                else -> if (state != SessionState.WEDGED) transition(SessionState.IDLE, "join ok")
            }
            if (audioThread === thread) audioThread = null
            return state != SessionState.WEDGED
        }
    }

    private fun joinQuiet(thread: Thread?, ms: Long) {
        if (thread == null) return
        try {
            thread.join(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun safeDispose() {
        try {
            sink.dispose()
        } catch (_: Exception) {
        }
    }

    private fun transition(next: SessionState, reason: String): Boolean {
        val prev = state
        if (prev == next) return true
        val legal = when (prev to next) {
            SessionState.IDLE to SessionState.STARTING,
            SessionState.FAILED to SessionState.STARTING,
            SessionState.STARTING to SessionState.RUNNING,
            SessionState.STARTING to SessionState.FAILED,
            SessionState.STARTING to SessionState.STOPPING,
            SessionState.STARTING to SessionState.IDLE,
            SessionState.RUNNING to SessionState.STOPPING,
            SessionState.RUNNING to SessionState.FAILED,
            SessionState.STOPPING to SessionState.IDLE,
            SessionState.STOPPING to SessionState.WEDGED,
            SessionState.WEDGED to SessionState.IDLE,
            SessionState.FAILED to SessionState.IDLE,
            -> true
            else -> false
        }
        if (!legal) {
            Log.e(TAG, "illegal transition $prev → $next ($reason)")
            return false
        }
        Log.i(TAG, "session $prev → $next ($reason)")
        state = next
        return true
    }

    companion object {
        private const val TAG = "MetromRunner"
    }
}
