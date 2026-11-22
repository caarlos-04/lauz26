package com.lauzhack.twin.ui.screens

import androidx.compose.material3.FilterChip
import com.lauzhack.twin.model.LuckMode
import com.lauzhack.twin.resources.welcome_luck_title
import com.lauzhack.twin.ui.i18n.keyString

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.app_tagline
import com.lauzhack.twin.resources.disclaimer
import com.lauzhack.twin.resources.welcome_badge
import com.lauzhack.twin.resources.welcome_demo_desc
import com.lauzhack.twin.resources.welcome_feature_cases_desc
import com.lauzhack.twin.resources.welcome_feature_cases_title
import com.lauzhack.twin.resources.welcome_feature_chat_desc
import com.lauzhack.twin.resources.welcome_feature_chat_title
import com.lauzhack.twin.resources.welcome_feature_mc_desc
import com.lauzhack.twin.resources.welcome_feature_mc_title
import com.lauzhack.twin.resources.welcome_play_demo
import com.lauzhack.twin.resources.welcome_start_interview
import com.lauzhack.twin.resources.welcome_subtitle
import com.lauzhack.twin.resources.welcome_title
import com.lauzhack.twin.ui.components.AppShell
import com.lauzhack.twin.ui.components.TopBarActions
import com.lauzhack.twin.ui.theme.LocalTwinColors
import com.lauzhack.twin.ui.theme.Space
import com.lauzhack.twin.ui.theme.figures
import org.jetbrains.compose.resources.stringResource

/** Start: an editorial cover (title, promise, two actions), a drawing of the map of lives and three numbered ideas. */
@Composable
fun WelcomeScreen(
    actions: TopBarActions,
    onStartInterview: () -> Unit,
    onPlayDemo: () -> Unit,
    luckMode: LuckMode = LuckMode.SAME_LEVEL,
    onLuckMode: (LuckMode) -> Unit = {},
) {
    AppShell(actions) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 760.dp
            Row(
                Modifier.padding(top = if (wide) Space.xxxl else Space.l, bottom = Space.xl),
                horizontalArrangement = Arrangement.spacedBy(Space.xxxl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(onStartInterview, onPlayDemo, luckMode, onLuckMode, Modifier.weight(1.15f))
                if (wide) TreeDrawing(Modifier.weight(1f).height(300.dp))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = Space.l)) {
            val features = listOf(
                stringResource(Res.string.welcome_feature_cases_title) to stringResource(Res.string.welcome_feature_cases_desc),
                stringResource(Res.string.welcome_feature_mc_title) to stringResource(Res.string.welcome_feature_mc_desc),
                stringResource(Res.string.welcome_feature_chat_title) to stringResource(Res.string.welcome_feature_chat_desc),
            )
            if (maxWidth > 720.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                    features.forEachIndexed { i, (title, desc) -> Feature(i + 1, title, desc, Modifier.weight(1f)) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xl)) {
                    features.forEachIndexed { i, (title, desc) -> Feature(i + 1, title, desc, Modifier.fillMaxWidth()) }
                }
            }
        }

        Text(stringResource(Res.string.disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Cover(onStartInterview: () -> Unit, onPlayDemo: () -> Unit, luckMode: LuckMode, onLuckMode: (LuckMode) -> Unit, modifier: Modifier) {
    Column(modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Text(stringResource(Res.string.welcome_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(Res.string.welcome_title), style = MaterialTheme.typography.displayMedium)
        Text(stringResource(Res.string.app_tagline), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Normal), color = LocalTwinColors.current.chosen)
        Text(stringResource(Res.string.welcome_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = onPlayDemo, shape = MaterialTheme.shapes.small, modifier = Modifier.height(44.dp)) {
                Text(stringResource(Res.string.welcome_play_demo), maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = onStartInterview, shape = MaterialTheme.shapes.small, modifier = Modifier.height(44.dp)) {
                Text(stringResource(Res.string.welcome_start_interview), maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(stringResource(Res.string.welcome_demo_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // How luck is drawn in the game (docs/decision-tree.md), chosen before it starts.
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(stringResource(Res.string.welcome_luck_title), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                LuckMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mode == luckMode,
                        onClick = { onLuckMode(mode) },
                        label = { Text(keyString("welcome_luck_${mode.name}")) },
                    )
                }
            }
            Text(keyString("welcome_luck_desc_${luckMode.name}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One of the three ideas: a number in the figures face, a title and a sentence. */
@Composable
private fun Feature(n: Int, title: String, desc: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(n.toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge.figures(), color = LocalTwinColors.current.chosen)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Illustration (not data): the map of lives. A tree of five decisions grows to the right; one life is drawn in the
 * accent, the others in hairlines.
 */
@Composable
private fun TreeDrawing(modifier: Modifier) {
    val accent = LocalTwinColors.current.chosen
    val line = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val depth = 5
        val leaves = 1 shl depth
        val colW = size.width / depth
        val rowH = size.height / leaves
        val path = listOf(0, 1, 1, 0, 1)                 // the highlighted life: B, A, B, B, A... from the root
        fun y(level: Int, index: Int): Float {
            val span = leaves shr level
            return (index * span + span / 2f) * rowH
        }
        var onPath = 0
        for (level in 0 until depth) {
            for (i in 0 until (1 shl level)) {
                val from = Offset(level * colW, y(level, i))
                for (c in 0..1) {
                    val child = i * 2 + c
                    val to = Offset((level + 1) * colW, y(level + 1, child))
                    val mid = Offset(from.x + colW / 2, to.y)
                    val highlighted = i == onPath && c == path[level]
                    val color = if (highlighted) accent else line
                    val w = if (highlighted) 2.5.dp.toPx() else 1.dp.toPx()
                    drawLine(color, from, Offset(mid.x, from.y), w, StrokeCap.Round)
                    drawLine(color, Offset(mid.x, from.y), mid, w, StrokeCap.Round)
                    drawLine(color, mid, to, w, StrokeCap.Round)
                }
                drawCircle(if (i == onPath) accent else dot, if (i == onPath) 4.dp.toPx() else 2.dp.toPx(), Offset(level * colW, y(level, i)))
            }
            onPath = onPath * 2 + path[level]
        }
        for (i in 0 until leaves) {
            drawCircle(if (i == onPath) accent else dot, if (i == onPath) 4.dp.toPx() else 2.dp.toPx(), Offset(depth * colW - 1f, y(depth, i)))
        }
    }
}
