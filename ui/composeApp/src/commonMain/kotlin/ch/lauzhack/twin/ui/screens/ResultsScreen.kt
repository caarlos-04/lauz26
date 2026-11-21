package ch.lauzhack.twin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.lauzhack.twin.model.Assumptions
import ch.lauzhack.twin.model.CaseType
import ch.lauzhack.twin.model.Event
import ch.lauzhack.twin.model.GameSession
import ch.lauzhack.twin.model.OptionPreview
import ch.lauzhack.twin.model.Turn
import ch.lauzhack.twin.resources.Res
import ch.lauzhack.twin.resources.action_restart
import ch.lauzhack.twin.resources.assumptions_title
import ch.lauzhack.twin.resources.chart_cash
import ch.lauzhack.twin.resources.chart_net_worth
import ch.lauzhack.twin.resources.chat_cta_desc
import ch.lauzhack.twin.resources.compare_alternative
import ch.lauzhack.twin.resources.compare_chosen
import ch.lauzhack.twin.resources.compare_reasons
import ch.lauzhack.twin.resources.compare_title
import ch.lauzhack.twin.resources.disclaimer
import ch.lauzhack.twin.resources.goal_median_month
import ch.lauzhack.twin.resources.goal_missed
import ch.lauzhack.twin.resources.goal_prob
import ch.lauzhack.twin.resources.goal_reached
import ch.lauzhack.twin.resources.goal_target
import ch.lauzhack.twin.resources.goals_reached_value
import ch.lauzhack.twin.resources.goals_title
import ch.lauzhack.twin.resources.kpi_vs_alternative
import ch.lauzhack.twin.resources.limitation_1
import ch.lauzhack.twin.resources.limitation_2
import ch.lauzhack.twin.resources.limitation_3
import ch.lauzhack.twin.resources.limitation_4
import ch.lauzhack.twin.resources.limitations_title
import ch.lauzhack.twin.resources.month_value
import ch.lauzhack.twin.resources.results_chat
import ch.lauzhack.twin.resources.results_headline
import ch.lauzhack.twin.resources.results_incomplete
import ch.lauzhack.twin.resources.results_title
import ch.lauzhack.twin.resources.stat_final_net_worth
import ch.lauzhack.twin.resources.stat_min_cash
import ch.lauzhack.twin.resources.stat_months_negative
import ch.lauzhack.twin.resources.stat_prob_negative
import ch.lauzhack.twin.resources.tab_assumptions
import ch.lauzhack.twin.resources.tab_overview
import ch.lauzhack.twin.resources.tab_tree
import ch.lauzhack.twin.resources.tree_title
import ch.lauzhack.twin.ui.GameResults
import ch.lauzhack.twin.ui.components.AppShell
import ch.lauzhack.twin.ui.components.DecisionTree
import ch.lauzhack.twin.ui.components.DeltaPill
import ch.lauzhack.twin.ui.components.FanChart
import ch.lauzhack.twin.ui.components.IconBadge
import ch.lauzhack.twin.ui.components.KpiTextTile
import ch.lauzhack.twin.ui.components.KpiTile
import ch.lauzhack.twin.ui.components.LabeledValue
import ch.lauzhack.twin.ui.components.Overline
import ch.lauzhack.twin.ui.components.PanelData
import ch.lauzhack.twin.ui.components.SectionCard
import ch.lauzhack.twin.ui.components.SurfaceCard
import ch.lauzhack.twin.ui.components.ThinProgress
import ch.lauzhack.twin.ui.components.TopBarActions
import ch.lauzhack.twin.ui.components.signColor
import ch.lauzhack.twin.ui.i18n.formatChf
import ch.lauzhack.twin.ui.i18n.formatPercent
import ch.lauzhack.twin.ui.i18n.keyString
import ch.lauzhack.twin.ui.theme.LocalTwinColors
import ch.lauzhack.twin.ui.theme.Space
import ch.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource

/** P9: KPIs, then tabs: overview (charts, goals, comparison), decision tree (what-if), assumptions. */
@Composable
fun ResultsScreen(
    actions: TopBarActions,
    panel: PanelData,
    session: GameSession,
    results: GameResults,
    assumptions: Assumptions,
    eventById: (String) -> Event?,
    playedTurns: List<Turn>?,
    isEdited: Boolean,
    pendingEvent: Event?,
    pendingPreviews: List<OptionPreview>,
    onEditTurn: (index: Int, optionId: String?, case: CaseType?) -> Unit,
    onDecide: (optionId: String) -> Unit,
    onResetPath: () -> Unit,
    onChat: () -> Unit,
    onRestart: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    AppShell(actions, panel = panel) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Overline(stringResource(Res.string.results_title))
                Text(stringResource(Res.string.results_headline), style = MaterialTheme.typography.headlineLarge)
            }
            TextButton(onClick = onRestart) { Text(stringResource(Res.string.action_restart)) }
        }

        KpiRow(session, results)

        if (pendingEvent != null) {
            SurfaceCard(border = MaterialTheme.colorScheme.tertiary, container = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    IconBadge(TwinIcons.Info, tint = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(Res.string.results_incomplete), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }

        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            listOf(
                Res.string.tab_overview to TwinIcons.Chart,
                Res.string.tab_tree to TwinIcons.Tree,
                Res.string.tab_assumptions to TwinIcons.Info,
            ).forEachIndexed { i, (label, icon) ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(stringResource(label)) },
                    icon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
                    selectedContentColor = MaterialTheme.colorScheme.onSurface,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (tab) {
            0 -> Overview(session, results, eventById)
            1 -> SectionCard(stringResource(Res.string.tree_title), icon = TwinIcons.Tree) {
                DecisionTree(session, playedTurns, isEdited, pendingEvent, pendingPreviews, eventById, onEditTurn, onDecide, onResetPath)
            }
            else -> AssumptionsTab(assumptions)
        }

        ChatCta(enabled = pendingEvent == null, onChat = onChat)
    }
}

@Composable
private fun KpiRow(session: GameSession, results: GameResults) {
    val stats = results.chosenStats
    val cmp = results.comparison
    val goalsTotal = session.profile.goals.size
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth > 700.dp
        val tiles: List<@Composable (Modifier) -> Unit> = listOf(
            { m ->
                KpiTile(
                    stringResource(Res.string.stat_final_net_worth), stats.finalNetWorth, m, icon = TwinIcons.TrendUp,
                    delta = cmp.deltaFinalNetWorth, caption = stringResource(Res.string.kpi_vs_alternative),
                )
            },
            { m ->
                KpiTile(
                    stringResource(Res.string.stat_min_cash), stats.minCash, m, icon = TwinIcons.Wallet,
                    valueColor = if (stats.minCash < 0) signColor(-1.0) else MaterialTheme.colorScheme.onSurface,
                    caption = stringResource(Res.string.month_value, stats.monthOfMinCash) + " · " +
                        stringResource(Res.string.stat_months_negative) + ": " + stats.monthsNegativeCash,
                )
            },
            { m -> KpiTextTile(stringResource(Res.string.stat_prob_negative), formatPercent(results.chosenSim.probNegativeCash), m, icon = TwinIcons.Shield) },
            { m ->
                KpiTextTile(
                    stringResource(Res.string.goals_title), stringResource(Res.string.goals_reached_value, stats.goalsReached.size, goalsTotal), m,
                    icon = TwinIcons.Target,
                )
            },
        )
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) { tiles.forEach { it(Modifier.weight(1f)) } }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                tiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) { pair.forEach { it(Modifier.weight(1f)) } }
                }
            }
        }
    }
}

@Composable
private fun Overview(session: GameSession, results: GameResults, eventById: (String) -> Event?) {
    val stats = results.chosenStats
    val sim = results.chosenSim
    val cmp = results.comparison
    SectionCard(stringResource(Res.string.chart_net_worth), icon = TwinIcons.TrendUp) {
        FanChart(sim.netWorth, results.alternativeSim.netWorth.p50)
    }
    SectionCard(stringResource(Res.string.chart_cash), icon = TwinIcons.Wallet) {
        FanChart(sim.cash, results.alternativeSim.cash.p50)
    }

    SectionCard(stringResource(Res.string.goals_title), icon = TwinIcons.Target) {
        session.profile.goals.forEach { goal ->
            val reached = goal.id in stats.goalsReached
            val prob = sim.goalProbabilities[goal.id]
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(keyString(goal.name), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(Res.string.goal_target, formatChf(goal.targetChf), goal.targetMonth),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusChip(reached)
                }
                if (prob != null) {
                    ThinProgress(prob.toFloat(), color = if (reached) LocalTwinColors.current.positive else MaterialTheme.colorScheme.tertiary)
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.goal_prob, formatPercent(prob)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        sim.goalMedianMonth[goal.id]?.let {
                            Text(stringResource(Res.string.goal_median_month, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(Space.xs))
            }
        }
    }

    SectionCard(stringResource(Res.string.compare_title), icon = TwinIcons.Layers) {
        LabeledValue(stringResource(Res.string.compare_chosen), formatChf(cmp.chosen.finalNetWorth))
        LabeledValue(stringResource(Res.string.compare_alternative), formatChf(cmp.alternative.finalNetWorth))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            DeltaPill(cmp.deltaFinalNetWorth)
        }
        Overline(stringResource(Res.string.compare_reasons))
        cmp.explanation.forEach { eventId ->
            val turn = session.turns.firstOrNull { it.eventId == eventId } ?: return@forEach
            val event = eventById(eventId)
            val chosenLabel = event?.options?.firstOrNull { it.id == turn.chosenOptionId }?.labelKey?.let { keyString(it) } ?: turn.chosenOptionId
            val delta = turn.stateAfter.netWorth - (turn.counterfactual[turn.resolvedCase]?.netWorth ?: turn.stateAfter.netWorth)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Column(Modifier.weight(1f)) {
                    Text(event?.let { keyString(it.titleKey) } ?: eventId, style = MaterialTheme.typography.titleSmall)
                    Text(chosenLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DeltaPill(delta)
            }
        }
    }
}

@Composable
private fun StatusChip(reached: Boolean) {
    val color = if (reached) LocalTwinColors.current.positive else LocalTwinColors.current.negative
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (reached) TwinIcons.Check else TwinIcons.Info, null, tint = color, modifier = Modifier.size(16.dp))
        Text(stringResource(if (reached) Res.string.goal_reached else Res.string.goal_missed), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun AssumptionsTab(assumptions: Assumptions) {
    SectionCard(stringResource(Res.string.assumptions_title), icon = TwinIcons.Layers) {
        // sourceNotes come from the data files (Dev C) and are shown as-is.
        assumptions.sourceNotes.forEach { Bullet(it) }
    }
    SectionCard(stringResource(Res.string.limitations_title), icon = TwinIcons.Shield) {
        listOf(Res.string.limitation_1, Res.string.limitation_2, Res.string.limitation_3, Res.string.limitation_4).forEach {
            Bullet(stringResource(it))
        }
        Text(stringResource(Res.string.disclaimer), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("•", color = MaterialTheme.colorScheme.secondary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatCta(enabled: Boolean, onChat: () -> Unit) {
    SurfaceCard(padding = Space.xl) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            IconBadge(TwinIcons.Chat, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.results_chat), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.chat_cta_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onChat, enabled = enabled, shape = MaterialTheme.shapes.small, modifier = Modifier.height(44.dp)) {
                Icon(TwinIcons.Send, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

