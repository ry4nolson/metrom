package com.metrom.shared.platform

enum class AudioRouteHint {
    SPEAKER,
    WIRED,
    BLUETOOTH,
    USB,
    UNKNOWN,
}

interface AssetIO {
    fun open(path: String): ByteArray?
    fun exists(path: String): Boolean
}

interface AudioSink {
    /** Start playback; returns preferred write chunk size in frames. */
    fun start(sampleRate: Int, channelCount: Int, preferredBufferFrames: Int): Int
    fun write(pcm: ShortArray, offset: Int, count: Int): Int
    fun playbackHeadFrames(): Long

    /**
     * Unblock a pending [write] and stop producing audio.
     * Must leave the underlying audio handle valid so an in-flight write can return
     * safely. Does **not** release native resources — that is [dispose] only.
     */
    fun stop()

    /**
     * Release the underlying audio handle and free native resources.
     * The only release path. Safe to call more than once. After this returns,
     * [write] / [stop] must be no-ops or return an error until the next [start].
     */
    fun dispose()

    fun routeHint(): AudioRouteHint
    fun setVolume(volume: Float) {}
}

interface LatencyPad {
    fun padMs(route: AudioRouteHint, bufferHintMs: Int): Long
}

interface MicCapture {
    fun capture(
        seconds: Float,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): FloatArray?
}

interface PrefsStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun contains(key: String): Boolean
}

interface Haptics {
    fun beat(isAccent: Boolean)
}

interface UiClock {
    fun nowMs(): Long
    fun postAt(uptimeMs: Long, block: () -> Unit)
    fun cancelAll()
}
