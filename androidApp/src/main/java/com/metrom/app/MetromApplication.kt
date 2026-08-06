package com.metrom.app

import android.app.Application
import android.content.Context

class MetromApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        private var instance: MetromApplication? = null

        fun appContext(): Context =
            checkNotNull(instance) { "MetromApplication not initialized" }
    }
}
