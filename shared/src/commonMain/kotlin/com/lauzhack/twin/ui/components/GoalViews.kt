package com.lauzhack.twin.ui.components

import com.lauzhack.twin.ui.theme.figures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Goal
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.goal_deadline
import com.lauzhack.twin.resources.goal_in_progress
import com.lauzhack.twin.resources.goal_measure_cash
import com.lauzhack.twin.resources.goal_measure_net_worth
import com.lauzhack.twin.resources.goal_missed_at
import com.lauzhack.twin.resources.goal_reached_at
import com.lauzhack.twin.ui.GoalStage
import com.lauzhack.twin.ui.GoalStatus
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.formatPercent
import com.lauzhack.twin.ui.i18n.goalLabel
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.progress
import com.lauzhack.twin.ui.theme.LocalTwinColors
import com.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource

@Composable
fun goalStageColor(stage: GoalStage): Color = when (stage) {
    GoalStage.REACHED -> LocalTwinColors.current.positive
    GoalStage.MISSED -> LocalTwinColors.current.negative
    GoalStage.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
}

@Composable
fun goalStageText(s: GoalStatus): String = when (s.stage) {
    GoalStage.REACHED -> stringResource(Res.string.goal_reached_at, s.whenAge ?: 0)
    GoalStage.MISSED -> stringResource(Res.string.goal_missed_at, s.whenAge ?: 0)
    GoalStage.IN_PROGRESS -> stringResource(Res.string.goal_in_progress)
}

/** "CHF 12'800 by 2029 (year 5) · high priority · measured on your cash". */
@Composable
fun goalDetails(s: GoalStatus): String =
    stringResource(Res.string.goal_deadline, formatChf(s.goal.targetChf), s.goal.targetDate.take(4), (s.targetMonth + 11) / 12) +
        " · " + keyString("goal_priority_${s.goal.priority.name}") +
        " · " + stringResource(if (s.onCash) Res.string.goal_measure_cash else Res.string.goal_measure_net_worth)

/** One goal in full: what, how much and by when, what it is measured on, progress now and whether it was reached. */
@Composable
fun GoalRow(s: GoalStatus) {
    val color = goalStageColor(s.stage)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(goalLabel(s.goal.name), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            GoalStageChip(s)
        }
        Text(goalDetails(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ThinProgress(s.progress.toFloat(), color = color)
        Row(Modifier.fillMaxWidth()) {
            Text(formatChf(s.valueChf) + " / " + formatChf(s.goal.targetChf), style = MaterialTheme.typography.labelSmall.figures(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(formatPercent(s.progress), style = MaterialTheme.typography.labelMedium.figures())
        }
    }
}

/** Compact goal for the side panel: name, %, bar and a one-line status. */
@Composable
fun GoalMini(s: GoalStatus) {
    val color = goalStageColor(s.stage)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(goalLabel(s.goal.name), style = MaterialTheme.typography.bodySmall)
            Text(formatPercent(s.progress), style = MaterialTheme.typography.labelMedium.figures(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ThinProgress(s.progress.toFloat(), color = color)
        Text(goalStageText(s) + " · " + formatChf(s.goal.targetChf) + " · " + s.goal.targetDate.take(4), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun GoalStageChip(s: GoalStatus) {
    val color = goalStageColor(s.stage)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (s.stage != GoalStage.IN_PROGRESS) {
            Icon(if (s.stage == GoalStage.REACHED) TwinIcons.Check else TwinIcons.Info, null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(goalStageText(s), style = MaterialTheme.typography.labelMedium, color = color)
    }
}
