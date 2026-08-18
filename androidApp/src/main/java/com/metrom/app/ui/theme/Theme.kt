package com.metrom.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.metrom.shared.theme.ColorTheme

@Composable
fun MetromTheme(
    theme: ColorTheme = ColorTheme.EMBER,
    content: @Composable () -> Unit,
) {
    val palette = theme.toPalette()
    val light = theme.isLight()
    val onAccent = if (light) palette.bone else palette.ink
    val scheme = if (light) {
        lightColorScheme(
            primary = palette.ember,
            onPrimary = onAccent,
            primaryContainer = palette.emberDeep,
            onPrimaryContainer = palette.bone,
            secondary = palette.copper,
            onSecondary = onAccent,
            tertiary = palette.pulse,
            background = palette.ink,
            onBackground = palette.bone,
            surface = palette.inkElevated,
            onSurface = palette.bone,
            onSurfaceVariant = palette.ash,
            outline = palette.inkLine,
            surfaceVariant = palette.inkElevated,
        )
    } else {
        darkColorScheme(
            primary = palette.ember,
            onPrimary = onAccent,
            primaryContainer = palette.emberDeep,
            onPrimaryContainer = palette.bone,
            secondary = palette.copper,
            onSecondary = onAccent,
            tertiary = palette.pulse,
            background = palette.ink,
            onBackground = palette.bone,
            surface = palette.inkElevated,
            onSurface = palette.bone,
            onSurfaceVariant = palette.ash,
            outline = palette.inkLine,
            surfaceVariant = palette.inkElevated,
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
    CompositionLocalProvider(LocalMetromPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MetromTypography,
            content = content
        )
    }
}
