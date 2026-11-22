package com.lauzhack.twin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/** Spacing scale (dp): 4-8-12-16-24-32. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

private val TwinShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun TwinTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTwinColors provides if (dark) DarkTwinColors else LightTwinColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = twinTypography(),
            shapes = TwinShapes,
            content = content,
        )
    }
}
