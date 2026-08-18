package com.metrom.app

import android.app.Application
import android.content.Context
import com.metrom.shared.db.MetromDatabase
import com.metrom.shared.db.createMetromSqlDriver
import com.metrom.shared.db.initMetromSqlDriver
import com.metrom.shared.db.openMetromDatabase

class MetromApplication : Application() {
    lateinit var metromDatabase: MetromDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        initMetromSqlDriver(this)
        metromDatabase = openMetromDatabase(createMetromSqlDriver())
    }

    companion object {
        @Volatile
        private var instance: MetromApplication? = null

        fun appContext(): Context =
            checkNotNull(instance) { "MetromApplication not initialized" }
    }
}
