package com.metrom.shared

import java.util.UUID

actual fun randomUuid(): String = UUID.randomUUID().toString()
