package com.metrom.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.timespec
import kotlin.concurrent.AtomicInt

/**
 * Monotonic clock + cancellable main-queue posts (mirrors Android Handler uptime).
 */
@OptIn(ExperimentalForeignApi::class)
class IosUiClock : UiClock {
    private val generation = AtomicInt(0)

    override fun nowMs(): Long = memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_MONOTONIC.toUInt(), ts.ptr)
        ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
    }

    override fun postAt(uptimeMs: Long, block: () -> Unit) {
        val gen = generation.value
        val delay = (uptimeMs - nowMs()).coerceAtLeast(0L)
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, delay * 1_000_000L),
            dispatch_get_main_queue(),
        ) {
            if (generation.value != gen) return@dispatch_after
            block()
        }
    }

    override fun cancelAll() {
        generation.addAndGet(1)
    }
}
