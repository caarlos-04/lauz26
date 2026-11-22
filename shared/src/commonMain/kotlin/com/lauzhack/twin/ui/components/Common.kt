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
import com.lauzhack.twin.ui.theme.TABULAR
import com.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource

/** Flat white card with a hairline border: the basic container of the design. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    padding: Dp = Space.l,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    container: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(Space.m), content = content)
    }
}

/** Card with an icon + title header. Kept for screens that group content in sections. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SurfaceCard(modifier) {
        if (title != null) SectionHeader(title, icon)
        content()
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector? = null, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        if (icon != null) IconBadge(icon, size = 28.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Small uppercase label used above values. */
@Composable
fun Overline(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
}

/** Rounded square with a tinted background holding an icon. */
@Composable
fun IconBadge(icon: ImageVector, size: Dp = 36.dp, tint: Color = MaterialTheme.colorScheme.secondary) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** Label on the left, value on the right. */
@Composable
fun LabeledValue(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR),
            fontWeight = FontWeight.SemiBold,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
        )
    }
}

/** Colored pill with an up/down arrow, e.g. "▲ +1'200 CHF". Color is never the only signal (arrow + sign). */
@Composable
fun DeltaPill(value: Double, text: String = formatChf(value, signed = true)) {
    val color = signColor(value)
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (value < 0) TwinIcons.TrendDown else TwinIcons.TrendUp, null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), color = color)
    }
}

/** Case name in a pill with a colored dot. */
@Composable
fun CaseBadge(case: CaseType, selected: Boolean = true) {
    val color = caseColor(case)
    Row(
        Modifier.clip(CircleShape)
            .background(if (selected) color.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(caseName(case), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Thin rounded progress bar (0..1). */
@Composable
fun ThinProgress(fraction: Float, color: Color = MaterialTheme.colorScheme.secondary, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(color))
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
