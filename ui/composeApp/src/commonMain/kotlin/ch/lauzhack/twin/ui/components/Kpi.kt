package ch.lauzhack.twin.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import ch.lauzhack.twin.ui.i18n.formatChf
import ch.lauzhack.twin.ui.theme.Space
import ch.lauzhack.twin.ui.theme.TABULAR

/** Amount that counts up/down to its new value when it changes (e.g. after a tree edit). */
@Composable
fun AnimatedMoney(
    value: Double,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    signed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val anim = remember { Animatable(value.toFloat()) }
    LaunchedEffect(value) {
        anim.animateTo(value.toFloat(), tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }
    Text(formatChf(anim.value.toDouble(), signed), style = style.copy(fontFeatureSettings = TABULAR), color = color, modifier = modifier)
}

/** KPI tile: overline label, big animated amount, optional delta pill and icon. */
@Composable
fun KpiTile(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    delta: Double? = null,
    deltaText: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    signed: Boolean = false,
    caption: String? = null,
) {
    SurfaceCard(modifier, padding = Space.l) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (icon != null) IconBadge(icon, size = 28.dp)
            Overline(label)
        }
        AnimatedMoney(value, MaterialTheme.typography.headlineMedium, valueColor, signed)
        if (delta != null) DeltaPill(delta, deltaText ?: formatChf(delta, signed = true))
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** KPI tile for plain text values (percentages, counts). */
@Composable
fun KpiTextTile(label: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null, caption: String? = null, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    SurfaceCard(modifier, padding = Space.l) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (icon != null) IconBadge(icon, size = 28.dp)
            Overline(label)
        }
        Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = TABULAR), color = valueColor)
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
