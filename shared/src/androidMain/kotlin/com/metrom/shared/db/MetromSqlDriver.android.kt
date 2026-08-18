package com.metrom.shared.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

private lateinit var appContext: Context

fun initMetromSqlDriver(context: Context) {
    appContext = context.applicationContext
}

actual fun createMetromSqlDriver(): SqlDriver {
    check(::appContext.isInitialized) { "initMetromSqlDriver(context) must run first" }
    return AndroidSqliteDriver(
        schema = MetromDatabase.Schema,
        context = appContext,
        name = "metrom.db",
        callback = object : AndroidSqliteDriver.Callback(MetromDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                db.execSQL("PRAGMA foreign_keys = OFF")
                db.execSQL("DROP TABLE IF EXISTS setlist_song")
                db.execSQL("DROP TABLE IF EXISTS song_section")
                db.execSQL("DROP TABLE IF EXISTS setlist")
                db.execSQL("DROP TABLE IF EXISTS song")
                db.execSQL("DROP TABLE IF EXISTS section")
                onCreate(db)
            }
        },
    )
}
