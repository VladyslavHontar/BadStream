package com.example.plohoystream.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PlohoyDarkColors = darkColorScheme(
    primary = OnSurfaceWhite,
    onPrimary = SurfaceBlack,
    background = SurfaceBlack,
    onBackground = OnSurfaceWhite,
    surface = SurfaceDark,
    onSurface = OnSurfaceWhite,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = OnSurfaceMuted,
    error = LiveRed,
)

/** App theme: dark, expressive. Wrap the whole UI tree. */
@Composable
fun PlohoyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PlohoyDarkColors,
        typography = PlohoyTypography,
        content = content,
    )
}
