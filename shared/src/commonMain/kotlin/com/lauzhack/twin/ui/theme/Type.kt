package com.lauzhack.twin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.inter_bold
import com.lauzhack.twin.resources.inter_medium
import com.lauzhack.twin.resources.inter_regular
import com.lauzhack.twin.resources.inter_semibold
import org.jetbrains.compose.resources.Font

// Inter (SIL Open Font License, see ui/licenses/Inter-OFL.txt).

@Composable
fun interFamily(): FontFamily = FontFamily(
    Font(Res.font.inter_regular, FontWeight.Normal),
    Font(Res.font.inter_medium, FontWeight.Medium),
    Font(Res.font.inter_semibold, FontWeight.SemiBold),
    Font(Res.font.inter_bold, FontWeight.Bold),
)

/** Tabular figures so amounts line up and do not jump while animating. */
const val TABULAR = "tnum"

@Composable
fun twinTypography(): Typography {
    val f = interFamily()
    fun s(size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0) =
        TextStyle(fontFamily = f, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = tracking.em)
    return Typography(
        displayLarge = s(48, FontWeight.Bold, 56, -0.02),
        displayMedium = s(40, FontWeight.Bold, 48, -0.02),
        displaySmall = s(32, FontWeight.Bold, 40, -0.02),
        headlineLarge = s(28, FontWeight.SemiBold, 36, -0.01),
        headlineMedium = s(24, FontWeight.SemiBold, 32, -0.01),
        headlineSmall = s(20, FontWeight.SemiBold, 28, -0.01),
        titleLarge = s(18, FontWeight.SemiBold, 26),
        titleMedium = s(16, FontWeight.SemiBold, 24),
        titleSmall = s(14, FontWeight.SemiBold, 20),
        bodyLarge = s(16, FontWeight.Normal, 24),
        bodyMedium = s(14, FontWeight.Normal, 20),
        bodySmall = s(13, FontWeight.Normal, 18),
        labelLarge = s(14, FontWeight.Medium, 20),
        labelMedium = s(12, FontWeight.Medium, 16, 0.02),
        labelSmall = s(11, FontWeight.SemiBold, 14, 0.06),
    )
}
