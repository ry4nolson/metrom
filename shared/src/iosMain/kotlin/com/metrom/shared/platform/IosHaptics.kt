package com.metrom.shared.platform

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

class IosHaptics : Haptics {
    private val light = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    private val medium = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)

    init {
        light.prepare()
        medium.prepare()
    }

    override fun beat(isAccent: Boolean) {
        if (isAccent) medium.impactOccurred() else light.impactOccurred()
    }
}
