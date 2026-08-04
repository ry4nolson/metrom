package com.metrom.app.engine

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Extra delay applied only to UI/haptic beat flashes so they align with when
 * the click is heard at the output device.
 *
 * Audio click placement stays sample-accurate inside [MetronomeEngine] — this
 * pad is never used for PCM write timing. Bluetooth A2DP/LE often adds
 * ~150–200ms after AudioTrack's playback head, so a fixed 12ms guess makes
 * the rail flash early on earbuds.
 *
 * Route is taken from [AudioTrack.getRoutedDevice] when available (updates if
 * the user plugs headphones or connects earbuds mid-session). Falls back to
 * [AudioManager] device list + output buffer properties, then [FALLBACK_MS].
 */
object VisualOutputLatency {
    /** Wired / speaker DAC pad when queries fail. */
    const val FALLBACK_MS = 12L

    private const val BLUETOOTH_MS = 170L
    private const val WIRED_MS = 8L
    private const val SPEAKER_MS = 12L
    private const val USB_MS = 10L

    fun padMs(track: AudioTrack, audioManager: AudioManager?): Long {
        val routed = routedDevice(track)
        if (routed != null) return padForType(routed.type)

        // No routed device yet (track not playing) — infer from available sinks.
        val inferred = inferOutputType(audioManager)
        if (inferred != null) return padForType(inferred)

        return FALLBACK_MS + bufferHintMs(audioManager)
    }

    fun isBluetoothType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> true
        else -> Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_BROADCAST
    }

    private fun padForType(type: Int): Long = when {
        isBluetoothType(type) -> BLUETOOTH_MS
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET -> WIRED_MS
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> USB_MS
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> SPEAKER_MS
        else -> FALLBACK_MS
    }

    private fun routedDevice(track: AudioTrack): AudioDeviceInfo? {
        return try {
            track.routedDevice
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Prefer Bluetooth if an A2DP/LE sink is present (media usually follows it),
     * else wired, else speaker.
     */
    private fun inferOutputType(audioManager: AudioManager?): Int? {
        if (audioManager == null) return null
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val types = devices.map { it.type }
        types.firstOrNull { isBluetoothType(it) }?.let { return it }
        types.firstOrNull {
            it == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it == AudioDeviceInfo.TYPE_USB_HEADSET
        }?.let { return it }
        types.firstOrNull {
            it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }?.let { return it }
        return types.firstOrNull()
    }

    /**
     * Small additive from [AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER] when
     * we lack a concrete route — never a substitute for Bluetooth compensation.
     */
    private fun bufferHintMs(audioManager: AudioManager?): Long {
        if (audioManager == null) return 0L
        val frames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toLongOrNull() ?: return 0L
        val rate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toLongOrNull() ?: 44_100L
        if (frames <= 0L || rate <= 0L) return 0L
        // A fraction of one mixer buffer — full buffer is already in playback-head math.
        return (frames * 1000L / rate / 4L).coerceIn(0L, 16L)
    }
}
