package com.lauzhack.twin.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// "Ink & cobalt": an editorial, Swiss-style palette. Warm paper and near-black ink do almost all the work; one cobalt
// accent marks what is yours (selection, your path, links). Money keeps its own meaning: green up, terracotta down,
// never cobalt. Surfaces are told apart by hairlines and small steps of lightness, not by shadows or tints.
//
// Contrast (WCAG, checked): ink on paper 16.4:1, muted text 6.1:1, cobalt 6.3:1, green / terracotta / grey on a card
// 5.3 / 5.0 / 4.9:1; in dark mode chalk on night 15.6:1, muted 7.1:1, light cobalt 7.4:1, every data color >= 6.7:1. Case colors keep their order (warm -> grey -> green) and differ in
// lightness too, so they stay apart for color-blind users; their names are always shown next to them.

private val Paper = Color(0xFFF5F3EE)
private val PaperRaised = Color(0xFFFBFAF7)
private val Ink = Color(0xFF161514)
private val InkMuted = Color(0xFF5F5B54)
private val Cobalt = Color(0xFF2B44E0)
private val Ochre = Color(0xFF8A5A00)

private val Night = Color(0xFF121110)
private val NightRaised = Color(0xFF191816)
private val Chalk = Color(0xFFECE9E2)
private val ChalkMuted = Color(0xFFA39E94)
private val CobaltLight = Color(0xFF8C9BFF)
private val OchreLight = Color(0xFFE0B45C)

internal val LightScheme = lightColorScheme(
    primary = Ink,                          // primary buttons are ink, like a printed page
    onPrimary = Paper,
    primaryContainer = Color(0xFFE4E1DA),
    onPrimaryContainer = Ink,
    secondary = Cobalt,                     // the one accent: selection, your path, links
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E6FB),
    onSecondaryContainer = Color(0xFF1A2A99),
    tertiary = Ochre,                       // "in progress", notices
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E7CF),
    onTertiaryContainer = Color(0xFF4A3000),
    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEAE7E0),
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = PaperRaised,
    surfaceContainerLow = Color(0xFFF0EDE7),
    surfaceContainer = Color(0xFFEAE7E0),
    surfaceContainerHigh = Color(0xFFE3DFD7),
    surfaceContainerHighest = Color(0xFFDCD8CF),
    outline = Color(0xFFBDB7AC),
    outlineVariant = Color(0xFFE0DCD3),
    error = Color(0xFFB42318),
    onError = Color.White,
)

internal val DarkScheme = darkColorScheme(
    primary = Chalk,
    onPrimary = Night,
    primaryContainer = Color(0xFF2C2A27),
    onPrimaryContainer = Chalk,
    secondary = CobaltLight,
    onSecondary = Color(0xFF0E1440),
    secondaryContainer = Color(0xFF232A5C),
    onSecondaryContainer = Color(0xFFDDE1FF),
    tertiary = OchreLight,
    onTertiary = Color(0xFF2A1C00),
    tertiaryContainer = Color(0xFF3A2C12),
    onTertiaryContainer = Color(0xFFF6E3BA),
    background = Night,
    onBackground = Chalk,
    surface = NightRaised,
    onSurface = Chalk,
    surfaceVariant = Color(0xFF24221F),
    onSurfaceVariant = ChalkMuted,
    surfaceContainerLowest = Night,
    surfaceContainerLow = Color(0xFF1D1C1A),
    surfaceContainer = Color(0xFF22201E),
    surfaceContainerHigh = Color(0xFF2A2825),
    surfaceContainerHighest = Color(0xFF33302C),
    outline = Color(0xFF4A4640),
    outlineVariant = Color(0xFF2C2A27),
    error = Color(0xFFF08A70),
    onError = Night,
)

/** App-specific semantic colors (cases, gains/losses, chart series). */
@Immutable
data class TwinColors(
    val pessimistic: Color,
    val neutral: Color,
    val optimistic: Color,
    val positive: Color,
    val negative: Color,
    val chosen: Color,       // your path (charts, map, selection)
    val alternative: Color,  // the other path / a second series
    val brand: Color,        // the logo mark
    val heroGradientEnd: Color,
)

internal val LightTwinColors = TwinColors(
    pessimistic = Color(0xFFC2410C),   // terracotta
    neutral = Color(0xFF736D64),       // warm grey
    optimistic = Color(0xFF11774A),    // deep green
    positive = Color(0xFF11774A),
    negative = Color(0xFFC2410C),
    chosen = Cobalt,
    alternative = Ochre,
    brand = Cobalt,
    heroGradientEnd = Paper,
)

internal val DarkTwinColors = TwinColors(
    pessimistic = Color(0xFFF08A5D),
    neutral = Color(0xFFA39E94),
    optimistic = Color(0xFF4CC38A),
    positive = Color(0xFF4CC38A),
    negative = Color(0xFFF08A5D),
    chosen = CobaltLight,
    alternative = OchreLight,
    brand = CobaltLight,
    heroGradientEnd = Night,
)

val LocalTwinColors = staticCompositionLocalOf { LightTwinColors }
