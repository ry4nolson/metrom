package com.metrom.shared.engine

import platform.posix.usleep

internal actual fun sleepMillis(ms: Long) {
    val micros = (ms * 1000L).coerceIn(0L, UInt.MAX_VALUE.toLong())
    usleep(micros.toUInt())
}
