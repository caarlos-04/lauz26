package com.lauzhack.twin.ui.screens

import androidx.compose.foundation.layout.Spacer
import com.lauzhack.twin.resources.action_chat_now
import com.lauzhack.twin.ui.cash
import com.lauzhack.twin.ui.netWorth
import com.lauzhack.twin.ui.i18n.localized
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.resolution_after
import com.lauzhack.twin.resources.resolution_before
import com.lauzhack.twin.resources.resolution_case_drawn
import com.lauzhack.twin.resources.resolution_counterfactual
import com.lauzhack.twin.resources.resolution_see_other
import com.lauzhack.twin.resources.resolution_drawing
import com.lauzhack.twin.resources.resolution_next_turn
import com.lauzhack.twin.resources.resolution_see_results
import com.lauzhack.twin.resources.resolution_title
import com.lauzhack.twin.resources.quiet_desc
import com.lauzhack.twin.resources.quiet_title
import com.lauzhack.twin.resources.state_cash
import com.lauzhack.twin.resources.state_net_worth
import com.lauzhack.twin.ui.components.AppShell
import com.lauzhack.twin.ui.components.CaseBadge
import com.lauzhack.twin.ui.components.KpiTile
import com.lauzhack.twin.ui.components.Overline
import com.lauzhack.twin.ui.components.PanelData
import com.lauzhack.twin.ui.components.SurfaceCard
import com.lauzhack.twin.ui.components.TopBarActions
import com.lauzhack.twin.ui.components.caseColor
import com.lauzhack.twin.ui.components.caseName
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.theme.Space
import com.lauzhack.twin.ui.theme.TwinIcons
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** P6 + P7: reveals the drawn case with a short animation and shows before/after. Quiet turns (no event) too. */
@Composable
fun ResolutionScreen(
    actions: TopBarActions,
    panel: PanelData,
    turn: Turn,
    before: FinancialStatement,
    event: Event?,
    monthsPerTurn: Int,
    isLastTurn: Boolean,
    onNext: () -> Unit,
    onOther: (() -> Unit)? = null,
    onChat: (() -> Unit)? = null,
    explain: (@Composable () -> Unit)? = null,   // "where do these numbers come from?" for this turn
) {
    var revealed by remember(turn) { mutableStateOf(false) }
    LaunchedEffect(turn) {
        delay(900)
        revealed = true
    }
    val option = event?.options?.firstOrNull { it.id == turn.chosenOptionId }
    AppShell(actions, panel = panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Overline(stringResource(Res.string.resolution_title))
            if (event == null) Text(stringResource(Res.string.quiet_title), style = MaterialTheme.typography.headlineLarge)
            event?.let { Text(localized(it.title), style = MaterialTheme.typography.headlineLarge) }
            option?.let { Text(localized(it.label), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        if (!revealed) {
            SurfaceCard(padding = Space.xxl) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.secondary)
                    Text(stringResource(Res.string.resolution_drawing), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        val resolved = turn.resolvedCase
        if (resolved == null) {
            AnimatedVisibility(revealed, enter = fadeIn(tween(350))) {
                SurfaceCard(padding = Space.xl) {
                    Text(stringResource(Res.string.quiet_desc, monthsPerTurn), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (resolved != null) AnimatedVisibility(revealed, enter = fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.94f)) {
            val color = caseColor(resolved)
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    // Colored band with the drawn case.
                    Row(
                        Modifier.fillMaxWidth().background(color.copy(alpha = 0.14f)).padding(Space.xl),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        CaseBadge(resolved)
                        Text(stringResource(Res.string.resolution_case_drawn, caseName(resolved)), style = MaterialTheme.typography.headlineSmall)
                    }
                    option?.cases?.get(resolved)?.narrative?.let {
                        Text(localized(it), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Space.xl))
                    }
                }
            }
        }
        AnimatedVisibility(revealed, enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(tween(400, delayMillis = 200)) { it / 6 }) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wide = maxWidth > 600.dp
                    val nwDelta = turn.stateAfter.netWorth - before.netWorth
                    val cashDelta = turn.stateAfter.cash - before.cash
                    val tiles: @Composable (Modifier) -> Unit = { m ->
                        KpiTile(
                            stringResource(Res.string.state_net_worth), turn.stateAfter.netWorth, m,
                            icon = TwinIcons.TrendUp, delta = nwDelta,
                            caption = stringResource(Res.string.resolution_before) + ": " + formatChf(before.netWorth),
                        )
                        KpiTile(
                            stringResource(Res.string.state_cash), turn.stateAfter.cash, m,
                            icon = TwinIcons.Wallet, delta = cashDelta,
                            caption = stringResource(Res.string.resolution_after, monthsPerTurn),
                        )
                    }
                    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) { tiles(Modifier.weight(1f)) }
                    else Column(verticalArrangement = Arrangement.spacedBy(Space.m)) { tiles(Modifier.fillMaxWidth()) }
                }
                explain?.invoke()
                resolved?.let { turn.counterfactual[it] }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Icon(TwinIcons.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text(stringResource(Res.string.resolution_counterfactual, formatChf(it.netWorth)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (onOther != null) {
                            OutlinedButton(onClick = onOther, shape = MaterialTheme.shapes.small) {
                                Text(stringResource(Res.string.resolution_see_other))
                            }
                        }
                    }
                }
                if (onChat != null) {
                    OutlinedButton(onClick = onChat, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(TwinIcons.Chat, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(Space.s))
                        Text(stringResource(Res.string.action_chat_now))
                    }
                }
                Button(onClick = onNext, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(if (isLastTurn) Res.string.resolution_see_results else Res.string.resolution_next_turn))
                }
            }
        }
    }
}
