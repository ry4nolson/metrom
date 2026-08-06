package com.metrom.shared.engine

internal actual fun sleepMillis(ms: Long) {
    Thread.sleep(ms)
}
