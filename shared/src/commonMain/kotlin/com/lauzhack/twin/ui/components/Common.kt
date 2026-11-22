package com.lauzhack.twin.ui.components

import com.lauzhack.twin.ui.cash
import com.lauzhack.twin.ui.netWorth
import com.lauzhack.twin.ui.monthlyNet
import com.lauzhack.twin.ui.current
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.state_cash
import com.lauzhack.twin.resources.state_monthly_net
import com.lauzhack.twin.resources.state_net_worth
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.theme.LocalTwinColors
import com.lauzhack.twin.ui.theme.Space
import com.lauzhack.twin.ui.theme.figures
import com.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The basic container: a raised sheet of paper with a hairline border and no shadow. Use it once per section; never
 * nest cards (group inside a card with spacing and dividers instead).
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    padding: Dp = Space.xl,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    container: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(Space.m), content = content)
    }
}

/** A section: a title and its content in one card. [icon] is accepted for compatibility but not drawn. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SurfaceCard(modifier) {
        if (title != null) SectionHeader(title)
        content()
    }
}

@Composable
fun SectionHeader(title: String, @Suppress("UNUSED_PARAMETER") icon: ImageVector? = null, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Small label above a value or a group, in sentence case (no shouting caps). */
@Composable
fun Overline(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

/** An icon on its own, in the muted ink: no tinted badge behind it. */
@Composable
fun IconBadge(icon: ImageVector, size: Dp = 36.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).coerceAtLeast(16.dp)))
}

/** Label on the left, figure on the right. */
@Composable
fun LabeledValue(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.figures(),
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
        )
    }
}

/** A signed change, e.g. "+1'200 CHF": coloured figures with an arrow (colour is never the only cue). */
@Composable
fun DeltaPill(value: Double, text: String = formatChf(value, signed = true)) {
    val color = signColor(value)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (value < 0) "↓" else "↑", style = MaterialTheme.typography.labelMedium, color = color)
        Text(text, style = MaterialTheme.typography.labelMedium.figures(), color = color)
    }
}

/** Case name with a small square of its colour. */
@Composable
fun CaseBadge(case: CaseType, @Suppress("UNUSED_PARAMETER") selected: Boolean = true) {
    val color = caseColor(case)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(caseName(case), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Thin progress bar (0..1) on a hairline track. */
@Composable
fun ThinProgress(fraction: Float, color: Color = MaterialTheme.colorScheme.secondary, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

@Composable
fun signColor(value: Double): Color {
    val c = LocalTwinColors.current
    return if (value < 0) c.negative else c.positive
}

@Composable
fun caseColor(case: CaseType): Color {
    val c = LocalTwinColors.current
    return when (case) {
        CaseType.PESSIMISTIC -> c.pessimistic
        CaseType.NEUTRAL -> c.neutral
        CaseType.OPTIMISTIC -> c.optimistic
    }
}

@Composable
fun caseName(case: CaseType): String = keyString("case_${case.name}")

/** Compact summary of a financial state: cash, net worth and monthly balance. */
@Composable
fun StateSummary(state: FinancialStatement) {
    LabeledValue(stringResource(Res.string.state_cash), formatChf(state.cash), if (state.cash < 0) signColor(-1.0) else Color.Unspecified)
    LabeledValue(stringResource(Res.string.state_net_worth), formatChf(state.netWorth))
    LabeledValue(stringResource(Res.string.state_monthly_net), formatChf(state.monthlyNet, signed = true), signColor(state.monthlyNet))
}
