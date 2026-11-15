package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NichDarkColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = ObsidianVoid,
    primaryContainer = ObsidianElevated,
    onPrimaryContainer = NeonGreen,
    secondary = NeonCyan,
    onSecondary = ObsidianVoid,
    secondaryContainer = ObsidianElevated,
    onSecondaryContainer = NeonCyan,
    tertiary = NeonBlue,
    onTertiary = TextPrimary,
    background = ObsidianVoid,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianElevated,
    onSurfaceVariant = TextSecondary,
    outline = ObsidianOutline,
    error = NeonRed,
    onError = ObsidianVoid
)

@Composable
fun NichAttendanceTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NichDarkColorScheme,
        typography = NichTypography,
        content = content
    )
}
