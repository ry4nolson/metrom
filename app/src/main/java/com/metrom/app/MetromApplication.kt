package com.metrom.app

import android.app.Application
import android.content.Context
import com.metrom.app.engine.ChugSamples

class MetromApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Warm chug caches off the main path once Context is available.
        ChugSamples.prepare(this)
    }

    companion object {
        @Volatile
        private var instance: MetromApplication? = null

        fun appContext(): Context =
            checkNotNull(instance) { "MetromApplication not initialized" }
    }
}
