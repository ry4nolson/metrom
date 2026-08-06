package com.metrom.app.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.metrom.shared.platform.Haptics

class AndroidHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    override fun beat(isAccent: Boolean) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val duration = if (isAccent) 28L else 14L
        val amplitude = if (isAccent) 180 else 90
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(duration)
        }
    }
}
