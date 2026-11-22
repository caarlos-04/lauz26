package com.lauzhack.twin.ui.screens

import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.GoalState
import com.lauzhack.twin.model.ParallelKind
import com.lauzhack.twin.ui.ChatBubble
import com.lauzhack.twin.ui.current
import com.lauzhack.twin.resources.cash_band_desc
import com.lauzhack.twin.resources.cash_band_title
import com.lauzhack.twin.resources.chat_offline_note
import com.lauzhack.twin.resources.chronicle_desc
import com.lauzhack.twin.resources.chronicle_rewrite
import com.lauzhack.twin.resources.chronicle_title
import com.lauzhack.twin.resources.chronicle_write
import com.lauzhack.twin.resources.driver_debt
import com.lauzhack.twin.resources.driver_event
import com.lauzhack.twin.resources.driver_expense
import com.lauzhack.twin.resources.driver_financing
import com.lauzhack.twin.resources.driver_income
import com.lauzhack.twin.resources.driver_investment
import com.lauzhack.twin.resources.driver_other
import com.lauzhack.twin.resources.drivers_title
import com.lauzhack.twin.resources.kpi_below_cushion
import com.lauzhack.twin.resources.kpi_financing_cost
import com.lauzhack.twin.resources.pl_optimal
import com.lauzhack.twin.resources.pl_other_first
import com.lauzhack.twin.resources.pl_subtitle
import com.lauzhack.twin.resources.pl_title
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.lauzhack.twin.ui.cash
import com.lauzhack.twin.ui.netWorth
import com.lauzhack.twin.ui.current
import com.lauzhack.twin.ui.i18n.localized
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
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.OptionPreview
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.action_restart
import com.lauzhack.twin.resources.assumptions_title
import com.lauzhack.twin.resources.chart_cash
import com.lauzhack.twin.resources.chart_net_worth
import com.lauzhack.twin.resources.chat_cta_desc
import com.lauzhack.twin.resources.compare_alternative
import com.lauzhack.twin.resources.compare_chosen
import com.lauzhack.twin.resources.compare_reasons
import com.lauzhack.twin.resources.compare_title
import com.lauzhack.twin.resources.disclaimer
import com.lauzhack.twin.resources.goal_median_month
import com.lauzhack.twin.resources.goal_missed
import com.lauzhack.twin.resources.goal_prob
import com.lauzhack.twin.resources.goal_reached
import com.lauzhack.twin.resources.goal_target
import com.lauzhack.twin.resources.goals_reached_value
import com.lauzhack.twin.resources.goals_title
import com.lauzhack.twin.resources.kpi_vs_alternative
import com.lauzhack.twin.resources.limitation_1
import com.lauzhack.twin.resources.limitation_2
import com.lauzhack.twin.resources.limitation_3
import com.lauzhack.twin.resources.limitation_4
import com.lauzhack.twin.resources.limitations_title
import com.lauzhack.twin.resources.month_value
import com.lauzhack.twin.resources.results_chat
import com.lauzhack.twin.resources.results_headline
import com.lauzhack.twin.resources.results_incomplete
import com.lauzhack.twin.resources.results_title
import com.lauzhack.twin.resources.stat_final_net_worth
import com.lauzhack.twin.resources.stat_min_cash
import com.lauzhack.twin.resources.stat_months_negative
import com.lauzhack.twin.resources.stat_prob_negative
import com.lauzhack.twin.resources.tab_assumptions
import com.lauzhack.twin.resources.tab_overview
import com.lauzhack.twin.resources.tab_tree
import com.lauzhack.twin.resources.tree_title

import com.lauzhack.twin.ui.components.AppShell
import com.lauzhack.twin.ui.components.DecisionTree
import com.lauzhack.twin.ui.components.DeltaPill
import com.lauzhack.twin.ui.components.FanChart
import com.lauzhack.twin.ui.components.IconBadge
import com.lauzhack.twin.ui.components.KpiTextTile
import com.lauzhack.twin.ui.components.KpiTile
import com.lauzhack.twin.ui.components.LabeledValue
import com.lauzhack.twin.ui.components.Overline
import com.lauzhack.twin.ui.components.PanelData
import com.lauzhack.twin.ui.components.SectionCard
import com.lauzhack.twin.ui.components.SurfaceCard
import com.lauzhack.twin.ui.components.ThinProgress
import com.lauzhack.twin.ui.components.TopBarActions
import com.lauzhack.twin.ui.components.signColor
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.formatPercent
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.theme.LocalTwinColors
import com.lauzhack.twin.ui.theme.Space
import com.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource

/** P8-P11: KPIs, then tabs: overview (cash band, story, goals, parallel lives, drivers), decision tree (what-if), assumptions. */
@Composable
fun ResultsScreen(
    actions: TopBarActions,
    panel: PanelData,
    session: GameSession,
    results: GameResult,
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
    chronicle: ChatBubble?,
    chronicleLoading: Boolean,
    onWriteChronicle: suspend () -> Unit,
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
            0 -> Overview(session, results, eventById, chronicle, chronicleLoading, onWriteChronicle)
            1 -> SectionCard(stringResource(Res.string.tree_title), icon = TwinIcons.Tree) {
                DecisionTree(session, playedTurns, isEdited, pendingEvent, pendingPreviews, eventById, onEditTurn, onDecide, onResetPath)
            }
            else -> AssumptionsTab(assumptions)
        }

        ChatCta(enabled = pendingEvent == null, onChat = onChat)
    }
}

@Composable
private fun KpiRow(session: GameSession, results: GameResult) {
    val stats = results.stats
    val other = results.parallelLives.firstOrNull { it.kind == ParallelKind.OTHER_FIRST_CHOICE }
    val goalsTotal = session.profile.goals.size
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth > 700.dp
        val tiles: List<@Composable (Modifier) -> Unit> = listOf(
            { m ->
                KpiTile(
                    stringResource(Res.string.stat_final_net_worth), stats.finalNetWorth, m, icon = TwinIcons.TrendUp,
                    delta = other?.let { stats.finalNetWorth - it.stats.finalNetWorth },
                    caption = other?.let { stringResource(Res.string.pl_other_first) },
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
            { m ->
                KpiTextTile(
                    stringResource(Res.string.kpi_below_cushion), stats.monthsBelowMinCash.toString(), m, icon = TwinIcons.Shield,
                    caption = stringResource(Res.string.kpi_financing_cost) + ": " + formatChf(stats.financingCostChf),
                )
            },
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
private fun Overview(
    session: GameSession,
    results: GameResult,
    eventById: (String) -> Event?,
    chronicle: ChatBubble?,
    chronicleLoading: Boolean,
    onWriteChronicle: suspend () -> Unit,
) {
    val final = session.current
    SectionCard(stringResource(Res.string.cash_band_title), icon = TwinIcons.Wallet) {
        Text(stringResource(Res.string.cash_band_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FanChart(results.cashBand, alternativeMedian = null)
    }

    ChronicleCard(chronicle, chronicleLoading, onWriteChronicle)

    SectionCard(stringResource(Res.string.goals_title), icon = TwinIcons.Target) {
        final.goals.forEach { goal ->
            val reached = goal.status == GoalState.REACHED
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
                ThinProgress(goal.progressRatio.toFloat(), color = if (reached) LocalTwinColors.current.positive else MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.height(Space.xs))
            }
        }
    }

    SectionCard(stringResource(Res.string.pl_title), icon = TwinIcons.Layers) {
        Text(stringResource(Res.string.pl_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledValue(stringResource(Res.string.compare_chosen), formatChf(results.stats.finalNetWorth))
        results.parallelLives.forEach { life ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(if (life.kind == ParallelKind.OTHER_FIRST_CHOICE) Res.string.pl_other_first else Res.string.pl_optimal),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    life.turns.forEach { t ->
                        val event = t.eventId?.let(eventById) ?: return@forEach
                        val option = event.options.firstOrNull { it.id == t.chosenOptionId } ?: return@forEach
                        Text(localized(event.title) + ": " + localized(option.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        formatChf(life.stats.finalNetWorth) + " · " + stringResource(Res.string.goals_reached_value, life.stats.goalsReached.size, session.profile.goals.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // Positive = you are ahead of that life.
                DeltaPill(results.stats.finalNetWorth - life.stats.finalNetWorth)
            }
        }
    }

    SectionCard(stringResource(Res.string.drivers_title), icon = TwinIcons.Chart) {
        results.attribution.items.sortedByDescending { abs(it.chf) }.take(6).forEach { item ->
            val event = item.sourceEventId?.let(eventById)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Column(Modifier.weight(1f)) {
                    Text(driverLabel(item.key), style = MaterialTheme.typography.titleSmall)
                    if (event != null) Text(localized(event.title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DeltaPill(item.chf)
            }
        }
    }
}

@Composable
private fun driverLabel(key: String): String = when {
    key.startsWith("income:") -> stringResource(Res.string.driver_income) + " (" + key.removePrefix("income:") + ")"
    key.startsWith("expense:") -> stringResource(Res.string.driver_expense) + " (" + key.removePrefix("expense:").lowercase() + ")"
    key.startsWith("event:") -> stringResource(Res.string.driver_event)
    key == "debt_interest" -> stringResource(Res.string.driver_debt)
    key == "financing_cost" -> stringResource(Res.string.driver_financing)
    key == "investment_return" -> stringResource(Res.string.driver_investment)
    else -> stringResource(Res.string.driver_other)
}

/** P11: the chronicle, written on demand by the future self (LLM or template). */
@Composable
private fun ChronicleCard(chronicle: ChatBubble?, loading: Boolean, onWrite: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    SectionCard(stringResource(Res.string.chronicle_title), icon = TwinIcons.Sparkle) {
        if (chronicle == null && !loading) {
            Text(stringResource(Res.string.chronicle_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.secondary)
        chronicle?.let {
            Text(it.message.text, style = MaterialTheme.typography.bodyMedium)
            if (it.offline) Text(stringResource(Res.string.chat_offline_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { scope.launch { onWrite() } }, enabled = !loading) {
            Text(stringResource(if (chronicle == null) Res.string.chronicle_write else Res.string.chronicle_rewrite))
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

