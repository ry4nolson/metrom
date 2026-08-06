package com.metrom.app.platform

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.metrom.shared.platform.AudioRouteHint
import com.metrom.shared.platform.LatencyPad

class AndroidLatencyPad(private val audioManager: AudioManager?) : LatencyPad {
    override fun padMs(route: AudioRouteHint, bufferHintMs: Int): Long = when (route) {
        AudioRouteHint.BLUETOOTH -> 170L
        AudioRouteHint.WIRED -> 8L
        AudioRouteHint.USB -> 10L
        AudioRouteHint.SPEAKER -> 12L
        AudioRouteHint.UNKNOWN -> 12L + bufferHintFromManager()
    }

    private fun bufferHintFromManager(): Long {
        if (audioManager == null) return 0L
        val frames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toLongOrNull() ?: return 0L
        val rate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toLongOrNull() ?: 44_100L
        if (frames <= 0L || rate <= 0L) return 0L
        return (frames * 1000L / rate / 4L).coerceIn(0L, 16L)
    }

    companion object {
        fun hintFromDeviceType(type: Int): AudioRouteHint = when {
            isBluetoothType(type) -> AudioRouteHint.BLUETOOTH
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRouteHint.WIRED
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioRouteHint.USB
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRouteHint.SPEAKER
            else -> AudioRouteHint.UNKNOWN
        }

        fun isBluetoothType(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID -> true
            else -> Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        }
    }
}
