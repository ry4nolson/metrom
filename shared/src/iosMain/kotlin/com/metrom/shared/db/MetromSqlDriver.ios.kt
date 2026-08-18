package com.metrom.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration

actual fun createMetromSqlDriver(): SqlDriver = NativeSqliteDriver(
    schema = MetromDatabase.Schema,
    name = "metrom.db",
    onConfiguration = { config ->
        config.copy(
            extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true),
            upgrade = { connection, _, _ ->
                wrapConnection(connection) { driver ->
                    driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
                    driver.execute(null, "DROP TABLE IF EXISTS setlist_song", 0)
                    driver.execute(null, "DROP TABLE IF EXISTS song_section", 0)
                    driver.execute(null, "DROP TABLE IF EXISTS setlist", 0)
                    driver.execute(null, "DROP TABLE IF EXISTS song", 0)
                    driver.execute(null, "DROP TABLE IF EXISTS section", 0)
                    MetromDatabase.Schema.create(driver)
                    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                }
            },
        )
    },
)
