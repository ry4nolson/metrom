package com.metrom.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MetromColorScheme = darkColorScheme(
    primary = Ember,
    onPrimary = Ink,
    primaryContainer = EmberDeep,
    onPrimaryContainer = Bone,
    secondary = Copper,
    onSecondary = Ink,
    tertiary = PulseAccent,
    background = Ink,
    onBackground = Bone,
    surface = InkElevated,
    onSurface = Bone,
    onSurfaceVariant = Ash,
    outline = InkLine,
    surfaceVariant = Color(0xFF1C1C22)
)

@Composable
fun MetromTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MetromColorScheme,
        typography = MetromTypography,
        content = content
    )
}
