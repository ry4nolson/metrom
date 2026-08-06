package com.metrom.shared.platform

class IosLatencyPad : LatencyPad {
    override fun padMs(route: AudioRouteHint, bufferHintMs: Int): Long = when (route) {
        AudioRouteHint.BLUETOOTH -> 170L
        AudioRouteHint.WIRED -> 8L
        AudioRouteHint.USB -> 10L
        AudioRouteHint.SPEAKER -> 12L
        AudioRouteHint.UNKNOWN -> 12L
    }
}
