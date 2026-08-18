package com.metrom.shared.library

import app.cash.sqldelight.db.SqlDriver

expect fun createTestSqlDriver(): SqlDriver
