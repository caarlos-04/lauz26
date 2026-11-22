package com.lauzhack.twin.ui.components

import com.lauzhack.twin.ui.netWorth
import com.lauzhack.twin.ui.stateBefore
import com.lauzhack.twin.ui.current
import com.lauzhack.twin.ui.i18n.localized
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.EventOption
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.OptionPreview
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.option_label
import com.lauzhack.twin.resources.tree_edited
import com.lauzhack.twin.resources.tree_hint
import com.lauzhack.twin.resources.tree_level
import com.lauzhack.twin.resources.choose_option
import com.lauzhack.twin.resources.tree_net_worth_after
import com.lauzhack.twin.resources.tree_new_question
import com.lauzhack.twin.resources.tree_pending_level
import com.lauzhack.twin.resources.tree_reset
import com.lauzhack.twin.resources.tree_start
import com.lauzhack.twin.resources.tree_would_be
import com.lauzhack.twin.ui.choice
import com.lauzhack.twin.resources.quiet_node
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.theme.LocalTwinColors
import org.jetbrains.compose.resources.stringResource

/**
 * Editable decision tree of a finished game. Each level is one turn with its two options:
 * the chosen branch continues the path, the other one shows what it would have given.
 * Tapping the other branch switches the option; the chips change the case of the chosen one.
 * If an edit leads to a question never answered, the tree ends with a pending level to decide it.
 */
@Composable
fun DecisionTree(
    session: GameSession,
    playedTurns: List<Turn>?,
    isEdited: Boolean,
    pendingEvent: Event?,
    pendingPreviews: List<OptionPreview>,
    eventById: (String) -> Event?,
    onEditTurn: (index: Int, optionId: String?, case: CaseType?) -> Unit,
    onDecide: (optionId: String) -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.tree_hint), style = MaterialTheme.typography.bodySmall)
        if (isEdited) {
            OutlinedButton(onClick = onReset, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(Res.string.tree_reset))
            }
        }

        // Root node, centered.
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            Card(
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            ) {
                Text(
                    stringResource(Res.string.tree_start, formatChf(session.initial.netWorth)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        var fromX = 0.5f
        session.turns.forEachIndexed { index, turn ->
            val event = turn.eventId?.let(eventById)
            if (event == null) {
                // Quiet period: no decision to edit, the path just continues.
                Connector(fromX, fromX, single = true)
                QuietNode(index, session.stateBefore(index).month, turn)
                return@forEachIndexed
            }
            val chosenIdx = event.options.indexOfFirst { it.id == turn.chosenOptionId }
            val chosenX = branchX(chosenIdx)
            Connector(fromX, chosenX)
            fromX = chosenX

            Text(
                stringResource(Res.string.tree_level, index + 1, session.stateBefore(index).month) + " · " + localized(event.title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                event.options.forEach { option ->
                    val mod = Modifier.weight(1f)
                    if (option.id == turn.chosenOptionId) {
                        val played = playedTurns?.getOrNull(index)
                        val badge = when {
                            played == null -> null
                            played.eventId != turn.eventId -> stringResource(Res.string.tree_new_question)
                            played.choice != turn.choice -> stringResource(Res.string.tree_edited)
                            else -> null
                        }
                        ChosenNode(option, turn, badge, { case -> onEditTurn(index, null, case) }, mod)
                    } else {
                        OtherNode(option, turn, { onEditTurn(index, option.id, null) }, mod)
                    }
                }
            }
        }

        if (pendingEvent != null) {
            val month = (session.turns.lastOrNull()?.stateAfter ?: session.initial).month
            Connector(fromX, chosenX = null)
            Text(
                stringResource(Res.string.tree_pending_level, session.turns.size + 1, month) + " · " + localized(pendingEvent.title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(localized(pendingEvent.description), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pendingEvent.options.forEach { option ->
                    PendingNode(option, pendingPreviews.firstOrNull { it.optionId == option.id }, { onDecide(option.id) }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Horizontal centre of the branch card: left half for the first option, right half for the second. */
private fun branchX(optionIndex: Int) = if (optionIndex <= 0) 0.25f else 0.75f

/**
 * Lines from the previous node to both branches; [chosenX] null = nothing chosen yet (both dashed).
 * [single] draws only the line to [chosenX] (quiet periods have no branches).
 */
@Composable
private fun Connector(fromX: Float, chosenX: Float?, single: Boolean = false) {
    val strong = LocalTwinColors.current.chosen
    val weak = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val start = Offset(size.width * fromX, 0f)
        (if (single && chosenX != null) listOf(chosenX) else listOf(0.25f, 0.75f)).forEach { x ->
            val end = Offset(size.width * x, size.height)
            if (x == chosenX) {
                drawLine(strong, start, end, strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            } else {
                drawLine(weak, start, end, strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
        }
    }
}

/** A quiet turn: a small dashed card on the path, not editable. */
@Composable
private fun QuietNode(index: Int, month: Int, turn: Turn) {
    Text(
        stringResource(Res.string.tree_level, index + 1, month) + " · " + stringResource(Res.string.quiet_node),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(Modifier.padding(12.dp)) {
            LabeledValue(stringResource(Res.string.tree_net_worth_after), formatChf(turn.stateAfter.netWorth))
        }
    }
}

@Composable
private fun ChosenNode(option: EventOption, turn: Turn, badge: String?, onCase: (CaseType) -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(2.dp, LocalTwinColors.current.chosen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.option_label, option.id).uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalTwinColors.current.chosen)
                if (badge != null) Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(localized(option.label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CaseType.entries.forEach { case ->
                    val color = caseColor(case)
                    FilterChip(
                        selected = case == turn.resolvedCase,
                        onClick = { onCase(case) },
                        label = { Text(caseName(case), style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = color.copy(alpha = 0.25f),
                            selectedLabelColor = color,
                        ),
                    )
                }
            }
            LabeledValue(stringResource(Res.string.tree_net_worth_after), formatChf(turn.stateAfter.netWorth))
        }
    }
}

@Composable
private fun OtherNode(option: EventOption, turn: Turn, onSelect: () -> Unit, modifier: Modifier) {
    val wouldBe = turn.resolvedCase?.let { turn.counterfactual[it] }?.netWorth
    OutlinedCard(
        onClick = onSelect,
        modifier = modifier.alpha(0.8f),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.option_label, option.id), style = MaterialTheme.typography.labelMedium)
            Text(localized(option.label), style = MaterialTheme.typography.titleSmall)
            if (wouldBe != null) {
                LabeledValue(stringResource(Res.string.tree_would_be), formatChf(wouldBe), signColor(wouldBe - turn.stateAfter.netWorth))
            }
        }
    }
}

@Composable
private fun PendingNode(option: EventOption, preview: OptionPreview?, onChoose: () -> Unit, modifier: Modifier) {
    OutlinedCard(modifier = modifier, border = BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.option_label, option.id), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            Text(localized(option.label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            preview?.let { p ->
                CaseType.entries.forEach { case ->
                    val c = p.perCase[case] ?: return@forEach
                    LabeledValue(caseName(case), formatChf(c.deltaNetWorth, signed = true), signColor(c.deltaNetWorth))
                }
            }
            Button(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.choose_option, option.id))
            }
        }
    }
}
