package com.klic.mobile.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary          = BrandPrimary,
    onPrimary        = BrandOnPrimary,
    background       = DarkBackground,
    onBackground     = DarkTextPrimary,
    surface          = DarkSurface,
    onSurface        = DarkTextPrimary,
    surfaceVariant   = DarkSurfaceRaised,
    onSurfaceVariant = DarkTextMuted,
    error            = BrandDanger,
    onError          = BrandOnPrimary,
    // Pure-grayscale containers: the M3 baseline tints these purple, which leaked
    // into the tab bar, modals and sheets. surfaceTint = surface kills elevation tint.
    surfaceTint             = DarkSurface,
    surfaceContainerLowest  = DarkBackground,
    surfaceContainerLow     = androidx.compose.ui.graphics.Color(0xFF141414),
    surfaceContainer        = DarkSurface,
    surfaceContainerHigh    = androidx.compose.ui.graphics.Color(0xFF222222),
    surfaceContainerHighest = DarkSurfaceRaised,
    secondaryContainer      = DarkSurfaceRaised,
    onSecondaryContainer    = DarkTextPrimary,
)

private val LightColors = lightColorScheme(
    primary          = BrandPrimary,
    onPrimary        = BrandOnPrimary,
    background       = LightBackground,
    onBackground     = LightTextPrimary,
    surface          = LightSurface,
    onSurface        = LightTextPrimary,
    surfaceVariant   = LightSurfaceRaised,
    onSurfaceVariant = LightTextMuted,
    error            = BrandDanger,
    onError          = BrandOnPrimary,
    // Pure-grayscale containers (tab bar, modals, sheets) — no purple baseline tint.
    surfaceTint             = LightSurface,
    surfaceContainerLowest  = LightSurface,
    surfaceContainerLow     = androidx.compose.ui.graphics.Color(0xFFFAFAFA),
    surfaceContainer        = androidx.compose.ui.graphics.Color(0xFFF0F0F0),
    surfaceContainerHigh    = androidx.compose.ui.graphics.Color(0xFFEBEBEB),
    surfaceContainerHighest = LightSurfaceRaised,
    secondaryContainer      = LightSurfaceRaised,
    onSecondaryContainer    = LightTextPrimary,
)

@Composable
fun KlicTheme(isDark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        typography  = KlicTypography,
        content     = content,
    )
}
