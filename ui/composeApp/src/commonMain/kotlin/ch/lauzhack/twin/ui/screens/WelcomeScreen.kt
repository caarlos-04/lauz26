package ch.lauzhack.twin.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ch.lauzhack.twin.resources.Res
import ch.lauzhack.twin.resources.app_tagline
import ch.lauzhack.twin.resources.disclaimer
import ch.lauzhack.twin.resources.welcome_badge
import ch.lauzhack.twin.resources.welcome_demo_desc
import ch.lauzhack.twin.resources.welcome_feature_cases_desc
import ch.lauzhack.twin.resources.welcome_feature_cases_title
import ch.lauzhack.twin.resources.welcome_feature_chat_desc
import ch.lauzhack.twin.resources.welcome_feature_chat_title
import ch.lauzhack.twin.resources.welcome_feature_mc_desc
import ch.lauzhack.twin.resources.welcome_feature_mc_title
import ch.lauzhack.twin.resources.welcome_play_demo
import ch.lauzhack.twin.resources.welcome_preview_label
import ch.lauzhack.twin.resources.welcome_start_interview
import ch.lauzhack.twin.resources.welcome_subtitle
import ch.lauzhack.twin.resources.welcome_title
import ch.lauzhack.twin.ui.components.AppShell
import ch.lauzhack.twin.ui.components.IconBadge
import ch.lauzhack.twin.ui.components.SurfaceCard
import ch.lauzhack.twin.ui.components.TopBarActions
import ch.lauzhack.twin.ui.theme.LocalTwinColors
import ch.lauzhack.twin.ui.theme.Space
import ch.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.math.sin

@Composable
fun WelcomeScreen(actions: TopBarActions, onStartInterview: () -> Unit, onPlayDemo: () -> Unit) {
    AppShell(actions) {
        Hero(onStartInterview, onPlayDemo)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val features: List<Triple<ImageVector, String, String>> = listOf(
                Triple(TwinIcons.Layers, stringResource(Res.string.welcome_feature_cases_title), stringResource(Res.string.welcome_feature_cases_desc)),
                Triple(TwinIcons.Chart, stringResource(Res.string.welcome_feature_mc_title), stringResource(Res.string.welcome_feature_mc_desc)),
                Triple(TwinIcons.Chat, stringResource(Res.string.welcome_feature_chat_title), stringResource(Res.string.welcome_feature_chat_desc)),
            )
            if (maxWidth > 720.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    features.forEach { (icon, title, desc) -> FeatureCard(icon, title, desc, Modifier.weight(1f)) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    features.forEach { (icon, title, desc) -> FeatureCard(icon, title, desc, Modifier.fillMaxWidth()) }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(TwinIcons.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(stringResource(Res.string.disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hero(onStartInterview: () -> Unit, onPlayDemo: () -> Unit) {
    val twin = LocalTwinColors.current
    val onHero = Color.White
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(twin.brand, twin.heroGradientEnd))),
    ) {
        val wide = maxWidth > 760.dp
        Row(Modifier.padding(if (wide) 40.dp else Space.xl), horizontalArrangement = Arrangement.spacedBy(Space.xxl), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                Text(
                    stringResource(Res.string.welcome_badge).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF6EE7B7),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Text(stringResource(Res.string.welcome_title), style = MaterialTheme.typography.displayMedium, color = onHero)
                Text(stringResource(Res.string.app_tagline), style = MaterialTheme.typography.titleLarge, color = Color(0xFF6EE7B7))
                Text(stringResource(Res.string.welcome_subtitle), style = MaterialTheme.typography.bodyLarge, color = onHero.copy(alpha = 0.78f))
                Spacer(Modifier.height(Space.xs))
                val buttons: @Composable () -> Unit = {
                    Button(
                        onClick = onPlayDemo,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White),
                        contentPadding = ButtonDefaults.ContentPadding,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(TwinIcons.Sparkle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(Res.string.welcome_play_demo), maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onStartInterview,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = onHero),
                        border = androidx.compose.foundation.BorderStroke(1.dp, onHero.copy(alpha = 0.35f)),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(TwinIcons.User, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(Res.string.welcome_start_interview), maxLines = 1, softWrap = false)
                    }
                }
                // Buttons wrap to a new line instead of squeezing their labels.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) { buttons() }
                Text(stringResource(Res.string.welcome_demo_desc), style = MaterialTheme.typography.bodySmall, color = onHero.copy(alpha = 0.6f))
            }
            if (wide) HeroPreview(Modifier.weight(1f))
        }
    }
}

/** Decorative, illustrative fan shape (not real data): communicates "range of futures" at a glance. */
@Composable
private fun HeroPreview(modifier: Modifier) {
    val accent = Color(0xFF34D399)
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.06f)).padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(stringResource(Res.string.welcome_preview_label).uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val n = 60
            fun x(i: Int) = size.width * i / (n - 1f)
            fun base(i: Int) = size.height * (0.85f - 0.55f * i / (n - 1f)) + 6f * sin(i / 4f)
            fun spread(i: Int) = size.height * 0.32f * i / (n - 1f)
            val band = Path().apply {
                moveTo(x(0), base(0))
                for (i in 1 until n) lineTo(x(i), base(i) - spread(i))
                for (i in n - 1 downTo 0) lineTo(x(i), base(i) + spread(i) * 0.8f)
                close()
            }
            drawPath(band, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.05f))))
            val median = Path().apply {
                moveTo(x(0), base(0))
                for (i in 1 until n) lineTo(x(i), base(i))
            }
            drawPath(median, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            for (k in 1..3) {
                val yy = size.height * k / 4f
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, yy), Offset(size.width, yy))
            }
        }
    }
}

@Composable
private fun FeatureCard(icon: ImageVector, title: String, desc: String, modifier: Modifier) {
    SurfaceCard(modifier) {
        IconBadge(icon)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
