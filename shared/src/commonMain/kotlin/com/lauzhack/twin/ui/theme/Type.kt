package com.lauzhack.twin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.plex_mono_medium
import com.lauzhack.twin.resources.plex_mono_regular
import com.lauzhack.twin.resources.plex_sans_bold
import com.lauzhack.twin.resources.plex_sans_medium
import com.lauzhack.twin.resources.plex_sans_regular
import com.lauzhack.twin.resources.plex_sans_semibold
import org.jetbrains.compose.resources.Font

// IBM Plex Sans for text and IBM Plex Mono for figures (SIL Open Font License, see licenses/IBM-Plex-OFL.txt).
// Amounts in a monospaced face line up in columns and never jump while they animate, like a printed statement.

@Composable
fun plexSans(): FontFamily = FontFamily(
    Font(Res.font.plex_sans_regular, FontWeight.Normal),
    Font(Res.font.plex_sans_medium, FontWeight.Medium),
    Font(Res.font.plex_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.plex_sans_bold, FontWeight.Bold),
)

@Composable
fun plexMono(): FontFamily = FontFamily(
    Font(Res.font.plex_mono_regular, FontWeight.Normal),
    Font(Res.font.plex_mono_medium, FontWeight.Medium),
    Font(Res.font.plex_mono_medium, FontWeight.SemiBold),
    Font(Res.font.plex_mono_medium, FontWeight.Bold),
)

/** Tabular figures (kept for text styles that are not monospaced). */
const val TABULAR = "tnum"

/** The monospaced family for figures, provided by [TwinTheme]. */
val LocalFigures = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/** This style with the figures face: use it for every amount, percentage and count. */
@Composable
fun TextStyle.figures(): TextStyle = copy(fontFamily = LocalFigures.current, fontFeatureSettings = TABULAR, letterSpacing = (-0.01).em)

@Composable
fun twinTypography(): Typography {
    val f = plexSans()
    fun s(size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0) =
        TextStyle(fontFamily = f, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = tracking.em)
    return Typography(
        displayLarge = s(56, FontWeight.SemiBold, 60, -0.03),
        displayMedium = s(44, FontWeight.SemiBold, 50, -0.03),
        displaySmall = s(34, FontWeight.SemiBold, 40, -0.025),
        headlineLarge = s(30, FontWeight.SemiBold, 36, -0.02),
        headlineMedium = s(24, FontWeight.SemiBold, 30, -0.015),
        headlineSmall = s(20, FontWeight.SemiBold, 26, -0.01),
        titleLarge = s(18, FontWeight.SemiBold, 24, -0.005),
        titleMedium = s(16, FontWeight.SemiBold, 22),
        titleSmall = s(14, FontWeight.SemiBold, 20),
        bodyLarge = s(16, FontWeight.Normal, 24),
        bodyMedium = s(14, FontWeight.Normal, 20),
        bodySmall = s(13, FontWeight.Normal, 18),
        labelLarge = s(14, FontWeight.Medium, 20),
        labelMedium = s(12, FontWeight.Medium, 16),
        labelSmall = s(11, FontWeight.Medium, 14, 0.01),
    )
}
