package com.metrom.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.metrom.shared.theme.ColorTheme

data class MetromPalette(
    val ink: Color,
    val inkElevated: Color,
    val inkLine: Color,
    val ash: Color,
    val mist: Color,
    val bone: Color,
    val ember: Color,
    val emberSoft: Color,
    val emberDeep: Color,
    val copper: Color,
    val pulse: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
)

fun ColorTheme.toPalette(): MetromPalette = MetromPalette(
    ink = hexColor(ink),
    inkElevated = hexColor(inkElevated),
    inkLine = hexColor(inkLine),
    ash = hexColor(ash),
    mist = hexColor(mist),
    bone = hexColor(bone),
    ember = hexColor(ember),
    emberSoft = hexColor(emberSoft),
    emberDeep = hexColor(emberDeep),
    copper = hexColor(copper),
    pulse = hexColor(pulse),
    backgroundTop = hexColor(backgroundTop),
    backgroundBottom = hexColor(backgroundBottom),
)

fun hexColor(hex: String): Color {
    val n = ColorTheme.normalizeHex(hex) ?: "000000"
    return Color(n.toLong(16) or 0xFF000000L)
}

val LocalMetromPalette = staticCompositionLocalOf { ColorTheme.EMBER.toPalette() }

val Ink: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.ink
val InkElevated: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.inkElevated
val InkLine: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.inkLine
val Ash: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.ash
val Mist: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.mist
val Bone: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.bone
val Ember: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.ember
val EmberSoft: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.emberSoft
val EmberDeep: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.emberDeep
val Copper: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.copper
val PulseAccent: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.pulse
val BackgroundTop: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.backgroundTop
val BackgroundBottom: Color
    @Composable @ReadOnlyComposable get() = LocalMetromPalette.current.backgroundBottom
