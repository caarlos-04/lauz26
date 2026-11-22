package com.lauzhack.twin.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// "Modern fintech" palette: deep navy brand, emerald accent, quiet neutrals.
// Data colors were checked with the dataviz palette validator (lightness band, CVD separation,
// contrast) against the light card surface and the dark card surface #16233A.

internal val LightScheme = lightColorScheme(
    primary = Color(0xFF0B1B33),            // navy: primary buttons, selected states
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F6EF),   // emerald tint
    onPrimaryContainer = Color(0xFF065F46),
    secondary = Color(0xFF0E9F6E),          // emerald accent
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6F6EF),
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = Color(0xFF4F6BED),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8ECFD),
    onTertiaryContainer = Color(0xFF26369A),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF0B1B33),
    surface = Color.White,
    onSurface = Color(0xFF0B1B33),
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = Color(0xFF5B6B82),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F4F8),
    surfaceContainerHigh = Color(0xFFEAEFF5),
    outline = Color(0xFFCBD3DE),
    outlineVariant = Color(0xFFE3E8EF),
    error = Color(0xFFC53030),
)

internal val DarkScheme = darkColorScheme(
    primary = Color(0xFF2BC48A),
    onPrimary = Color(0xFF04261A),
    primaryContainer = Color(0xFF12392C),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF1FA877),
    onSecondary = Color(0xFF04261A),
    secondaryContainer = Color(0xFF12392C),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFF8B9CFF),
    onTertiary = Color(0xFF101B5C),
    tertiaryContainer = Color(0xFF243074),
    onTertiaryContainer = Color(0xFFDDE2FF),
    background = Color(0xFF0B1422),
    onBackground = Color(0xFFE6ECF5),
    surface = Color(0xFF121D30),
    onSurface = Color(0xFFE6ECF5),
    surfaceVariant = Color(0xFF16233A),
    onSurfaceVariant = Color(0xFF9AA8BD),
    surfaceContainerLowest = Color(0xFF0E1828),
    surfaceContainerLow = Color(0xFF121D30),
    surfaceContainer = Color(0xFF16233A),
    surfaceContainerHigh = Color(0xFF1C2B45),
    outline = Color(0xFF3A4A66),
    outlineVariant = Color(0xFF22314A),
    error = Color(0xFFFF8A80),
)

/** App-specific semantic colors (cases, gains/losses, chart series). */
@Immutable
data class TwinColors(
    val pessimistic: Color,
    val neutral: Color,
    val optimistic: Color,
    val positive: Color,
    val negative: Color,
    val chosen: Color,       // chart: chosen path
    val alternative: Color,  // chart: alternative path
    val brand: Color,        // logo / hero
    val heroGradientEnd: Color,
)

internal val LightTwinColors = TwinColors(
    pessimistic = Color(0xFFD9622B),
    neutral = Color(0xFF64748B),
    optimistic = Color(0xFF0E9F6E),
    positive = Color(0xFF0E8A60),
    negative = Color(0xFFC53030),
    chosen = Color(0xFF0E9F6E),
    alternative = Color(0xFF4F6BED),
    brand = Color(0xFF0B1B33),
    heroGradientEnd = Color(0xFF123B5C),
)

internal val DarkTwinColors = TwinColors(
    pessimistic = Color(0xFFD86E3A),
    neutral = Color(0xFF94A3B8),
    optimistic = Color(0xFF1FA877),
    positive = Color(0xFF34D399),
    negative = Color(0xFFFF8A80),
    chosen = Color(0xFF1FA877),
    alternative = Color(0xFF6E80EE),
    brand = Color(0xFF0B1B33),
    heroGradientEnd = Color(0xFF14405F),
)

val LocalTwinColors = staticCompositionLocalOf { LightTwinColors }
