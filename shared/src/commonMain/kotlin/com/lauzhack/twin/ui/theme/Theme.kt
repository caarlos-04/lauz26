package com.lauzhack.twin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/** Spacing scale (dp): 4-8-12-16-24-32-48. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

// Small, consistent radii: crisp like paper, not bubbly.
private val TwinShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

@Composable
fun TwinTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTwinColors provides if (dark) DarkTwinColors else LightTwinColors,
        LocalFigures provides plexMono(),
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = twinTypography(),
            shapes = TwinShapes,
            content = content,
        )
    }
}
