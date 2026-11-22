package com.lauzhack.twin.ui.screens

import com.lauzhack.twin.ui.theme.figures
import androidx.compose.ui.text.style.TextAlign
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.ui.current
import com.lauzhack.twin.ui.i18n.localized
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.IntrinsicSize
import com.lauzhack.twin.model.Action
import com.lauzhack.twin.model.NOTHING
import com.lauzhack.twin.model.Law
import com.lauzhack.twin.model.Uncertain
import com.lauzhack.twin.engine.formatValue
import com.lauzhack.twin.ui.i18n.LocalLang
import com.lauzhack.twin.resources.dilemma_range
import com.lauzhack.twin.resources.dilemma_uncertain
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.choose_action
import com.lauzhack.twin.resources.dilemma_certain
import com.lauzhack.twin.resources.dilemma_count
import com.lauzhack.twin.resources.dilemma_title
import com.lauzhack.twin.resources.dilemma_hint
import com.lauzhack.twin.resources.dilemma_turn
import com.lauzhack.twin.resources.dilemma_lived
import com.lauzhack.twin.ui.components.AppShell
import com.lauzhack.twin.ui.components.DeltaPill
import com.lauzhack.twin.ui.components.IconBadge
import com.lauzhack.twin.ui.components.LabeledValue
import com.lauzhack.twin.ui.components.Overline
import com.lauzhack.twin.ui.components.Pill
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

/**
 * P4 + P5: every action the person can take at this moment (the pool, "carry on as you are" first) and the choice.
 * Nothing about the outcome is shown before deciding: only what the action is and which numbers depend on luck (the
 * usual range of the data). The balances come after the decision, on the resolution screen.
 */
@Composable
fun DilemmaScreen(
    actions: TopBarActions,
    panel: PanelData,
    session: GameSession,
    available: List<Action>,
    onChoose: (actionId: String) -> Unit,
    explored: Set<String> = emptySet(),   // actions already taken here: their branch exists
) {
    val state = session.current
    AppShell(actions, panel = panel) {
        // The question on the page itself, like a headline: no card around it.
        Column(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Overline(stringResource(Res.string.dilemma_turn, session.turns.size + 1, session.config.maxTurns, state.month))
            Text(stringResource(Res.string.dilemma_title), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(Res.string.dilemma_count, available.size), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(TwinIcons.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                stringResource(Res.string.dilemma_hint) + " " + keyString("dilemma_luck_${session.luckMode.name}"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Two columns when there is room, one on phones. "Carry on as you are" comes first.
        val ordered = available.sortedBy { if (it.id == NOTHING) 0 else 1 }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth > 620.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                ordered.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        row.forEach { action ->
                            ActionCard(action, action.id in explored, { onChoose(action.id) }, Modifier.weight(1f).fillMaxHeight())
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(action: Action, explored: Boolean, onChoose: () -> Unit, modifier: Modifier) {
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
            if (explored) Pill(stringResource(Res.string.dilemma_lived))
            Text(localized(action.title), style = MaterialTheme.typography.headlineSmall)
            Text(localized(action.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action.id != NOTHING) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (action.uncertain.isEmpty()) {
                    Text(stringResource(Res.string.dilemma_certain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Overline(stringResource(Res.string.dilemma_uncertain))
                    action.uncertain.forEach { UncertainLine(it) }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onChoose, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(Res.string.choose_action))
            }
        }
    }
}

/** "Starting salary: usually between CHF 4’500 and 7’500" (the low and high quantiles of the data); a flat: just its name. */
@Composable
private fun UncertainLine(u: Uncertain) {
    val lang = LocalLang.current
    val label = keyString("uncertain_${u.name}")
    val text = if (u.law == Law.QUANTILE) label
               else stringResource(Res.string.dilemma_range, label, shownValue(u.format, u.low.value, lang), shownValue(u.format, u.high.value, lang))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", color = MaterialTheme.colorScheme.secondary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun shownValue(format: String, v: Double, lang: com.lauzhack.twin.model.Lang): String =
    if (format == "chf") "CHF " + formatValue(format, v, lang) else formatValue(format, v, lang)
