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
import com.lauzhack.twin.model.NOTHING
import com.lauzhack.twin.ui.Catalog
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.resolution_after
import com.lauzhack.twin.resources.resolution_before
import com.lauzhack.twin.resources.resolution_luck
import com.lauzhack.twin.resources.dilemma_certain
import com.lauzhack.twin.ui.components.LuckBadge
import com.lauzhack.twin.ui.i18n.LocalLang
import com.lauzhack.twin.resources.resolution_shock
import com.lauzhack.twin.resources.resolution_try_other
import com.lauzhack.twin.resources.resolution_drawing
import com.lauzhack.twin.resources.resolution_next_turn
import com.lauzhack.twin.resources.resolution_see_results
import com.lauzhack.twin.resources.resolution_title
import com.lauzhack.twin.resources.quiet_desc
import com.lauzhack.twin.resources.quiet_title
import com.lauzhack.twin.resources.state_cash
import com.lauzhack.twin.resources.state_net_worth
import com.lauzhack.twin.ui.components.AppShell
import com.lauzhack.twin.ui.components.KpiTile
import com.lauzhack.twin.ui.components.Overline
import com.lauzhack.twin.ui.components.PanelData
import com.lauzhack.twin.ui.components.SurfaceCard
import com.lauzhack.twin.ui.components.TopBarActions
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.theme.Space
import com.lauzhack.twin.ui.theme.TwinIcons
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** P6 + P7: after the decision, reveals the luck of the moment and what it gave (the outcome with its drawn numbers),
 *  a shock if one happened, and then the balances before/after. Nothing of this is shown before deciding.
 *  "Carry on as you are" (NOTHING) is shown as a quiet period. */
@Composable
fun ResolutionScreen(
    actions: TopBarActions,
    panel: PanelData,
    turn: Turn,
    before: FinancialStatement,
    catalog: Catalog,
    monthsPerTurn: Int,
    isLastTurn: Boolean,
    onNext: () -> Unit,
    onOther: (() -> Unit)? = null,           // go back to the moment of this decision and decide something else
    onChat: (() -> Unit)? = null,
    explain: (@Composable () -> Unit)? = null,   // "where do these numbers come from?" for this turn
) {
    var revealed by remember(turn) { mutableStateOf(false) }
    LaunchedEffect(turn) {
        delay(900)
        revealed = true
    }
    val action = catalog.action(turn.actionId).takeIf { turn.actionId != NOTHING }
    val shock = catalog.shock(turn.shockId)
    AppShell(actions, panel = panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Overline(stringResource(Res.string.resolution_title))
            if (action == null) Text(stringResource(Res.string.quiet_title), style = MaterialTheme.typography.headlineLarge)
            action?.let { Text(localized(it.title), style = MaterialTheme.typography.headlineLarge) }
        }

        if (!revealed) {
            SurfaceCard(padding = Space.xxl) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.secondary)
                    Text(stringResource(Res.string.resolution_drawing), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        val lang = LocalLang.current
        if (action == null) {
            AnimatedVisibility(revealed, enter = fadeIn(tween(350))) {
                SurfaceCard(padding = Space.xl) {
                    Text(stringResource(Res.string.quiet_desc, monthsPerTurn), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (action != null) AnimatedVisibility(revealed, enter = fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.94f)) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    // The luck of the moment: the percentile every uncertain number was drawn at.
                    Row(
                        Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, top = Space.xl),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        if (action.uncertain.isNotEmpty()) LuckBadge(turn.luck)
                        Text(
                            if (action.uncertain.isNotEmpty()) stringResource(Res.string.resolution_luck, kotlin.math.round(turn.luck * 100).toInt())
                            else stringResource(Res.string.dilemma_certain),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        catalog.outcome(turn.actionId, turn.values, lang).orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = Space.xl, end = Space.xl, top = Space.m, bottom = Space.xl),
                    )
                }
            }
        }
        if (shock != null) AnimatedVisibility(revealed, enter = fadeIn(tween(350, delayMillis = 150))) {
            SurfaceCard(padding = Space.xl) {
                Text(stringResource(Res.string.resolution_shock, localized(shock.title)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
                Text(catalog.outcome(turn.shockId, turn.shockValues, lang).orEmpty(), style = MaterialTheme.typography.bodyMedium)
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
                        if (kotlin.math.abs(turn.stateAfter.cash - turn.stateAfter.netWorth) >= 1.0) KpiTile(
                            stringResource(Res.string.state_cash), turn.stateAfter.cash, m,
                            icon = TwinIcons.Wallet, delta = cashDelta,
                            caption = stringResource(Res.string.resolution_after, monthsPerTurn),
                        )
                    }
                    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) { tiles(Modifier.weight(1f)) }
                    else Column(verticalArrangement = Arrangement.spacedBy(Space.m)) { tiles(Modifier.fillMaxWidth()) }
                }
                explain?.invoke()
                if (onOther != null) {
                    OutlinedButton(onClick = onOther, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(Res.string.resolution_try_other))
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
