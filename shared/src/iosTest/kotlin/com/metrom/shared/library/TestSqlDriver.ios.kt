package com.metrom.shared.library

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import com.metrom.shared.db.MetromDatabase

actual fun createTestSqlDriver(): SqlDriver = NativeSqliteDriver(
    DatabaseConfiguration(
        name = "metrom-library-test.db",
        version = MetromDatabase.Schema.version.toInt(),
        create = { connection ->
            wrapConnection(connection) { MetromDatabase.Schema.create(it) }
        },
        upgrade = { connection, _, _ ->
            wrapConnection(connection) { MetromDatabase.Schema.create(it) }
        },
        inMemory = true,
        extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true),
    ),
)
