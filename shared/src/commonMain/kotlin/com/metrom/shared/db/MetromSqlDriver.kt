package com.metrom.shared.db

import app.cash.sqldelight.db.SqlDriver

expect fun createMetromSqlDriver(): SqlDriver

fun openMetromDatabase(): MetromDatabase = openMetromDatabase(createMetromSqlDriver())

fun openMetromDatabase(driver: SqlDriver): MetromDatabase {
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    return MetromDatabase(driver)
}
