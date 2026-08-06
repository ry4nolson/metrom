package com.metrom.app.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.metrom.shared.platform.UiClock

class AndroidUiClock : UiClock {
    private val handler = Handler(Looper.getMainLooper())

    override fun nowMs(): Long = SystemClock.uptimeMillis()

    override fun postAt(uptimeMs: Long, block: () -> Unit) {
        handler.postAtTime(block, uptimeMs)
    }

    override fun cancelAll() {
        handler.removeCallbacksAndMessages(null)
    }
}
