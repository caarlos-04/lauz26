package com.lauzhack.twin.ui.screens

import com.lauzhack.twin.ui.current
import com.lauzhack.twin.ui.i18n.localized
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.OptionPreview
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.choose_option
import com.lauzhack.twin.resources.delta_net_worth
import com.lauzhack.twin.resources.dilemma_hint
import com.lauzhack.twin.resources.dilemma_turn
import com.lauzhack.twin.resources.min_cash
import com.lauzhack.twin.resources.option_label
import com.lauzhack.twin.ui.components.AppShell
import com.lauzhack.twin.ui.components.CaseBadge
import com.lauzhack.twin.ui.components.CaseDistributionBar
import com.lauzhack.twin.ui.components.DeltaPill
import com.lauzhack.twin.ui.components.IconBadge
import com.lauzhack.twin.ui.components.LabeledValue
import com.lauzhack.twin.ui.components.Overline
import com.lauzhack.twin.ui.components.PanelData
import com.lauzhack.twin.ui.components.SurfaceCard
import com.lauzhack.twin.ui.components.TopBarActions
import com.lauzhack.twin.ui.components.signColor
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.formatPercent
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.theme.LocalTwinColors
import com.lauzhack.twin.ui.theme.Space
import com.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource

/** P4 + P5: shows the event, both options with their 3 cases, and lets the user choose. */
@Composable
fun DilemmaScreen(
    actions: TopBarActions,
    panel: PanelData,
    session: GameSession,
    event: Event,
    previews: List<OptionPreview>,
    onChoose: (optionId: String) -> Unit,
) {
    val state = session.turns.lastOrNull()?.stateAfter ?: session.initial
    AppShell(actions, panel = panel) {
        SurfaceCard(padding = Space.xl) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                IconBadge(TwinIcons.Calendar)
                Overline(stringResource(Res.string.dilemma_turn, session.turns.size + 1, session.config.maxTurns, state.month))
            }
            Text(localized(event.title), style = MaterialTheme.typography.headlineLarge)
            Text(localized(event.description), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(TwinIcons.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(stringResource(Res.string.dilemma_hint, session.config.monthsPerTurn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Side by side when there is room, stacked on phones.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 620.dp
            val cards: @Composable (Modifier) -> Unit = { mod ->
                event.options.forEach { option ->
                    val preview = previews.firstOrNull { it.optionId == option.id } ?: return@forEach
                    OptionCard(option.id, localized(option.label), preview, { onChoose(option.id) }, mod)
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) { cards(Modifier.weight(1f)) }
            else Column(verticalArrangement = Arrangement.spacedBy(Space.l)) { cards(Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun OptionCard(id: String, label: String, preview: OptionPreview, onChoose: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = LocalTwinColors.current.chosen
    Card(
        modifier = modifier.hoverable(interaction),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (hovered) 2.dp else 1.dp, if (hovered) accent else MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (hovered) 6.dp else 0.dp),
    ) {
        Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Overline(stringResource(Res.string.option_label, id), color = accent)
            Text(label, style = MaterialTheme.typography.headlineSmall)
            CaseDistributionBar(preview.perCase.mapValues { it.value.prob })
            CaseType.entries.forEach { case ->
                val p = preview.perCase[case] ?: return@forEach
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CaseBadge(case)
                    Spacer(Modifier.weight(1f))
                    Text(formatPercent(p.prob), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(localized(p.narrative), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.delta_net_worth), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    DeltaPill(p.deltaNetWorth)
                }
                LabeledValue(stringResource(Res.string.min_cash), formatChf(p.minCash), if (p.minCash < 0) signColor(p.minCash) else Color.Unspecified)
            }
            Spacer(Modifier.height(Space.xs))
            Button(onClick = onChoose, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(Res.string.choose_option, id))
            }
        }
    }
}
