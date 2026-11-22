package com.lauzhack.twin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.engine.formatValue
import com.lauzhack.twin.engine.resolve
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.model.Uncertain
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.driver_debt
import com.lauzhack.twin.resources.driver_financing
import com.lauzhack.twin.resources.driver_investment
import com.lauzhack.twin.resources.eff_duration
import com.lauzhack.twin.resources.eff_employment
import com.lauzhack.twin.resources.eff_expense
import com.lauzhack.twin.resources.eff_fact
import com.lauzhack.twin.resources.eff_flag
import com.lauzhack.twin.resources.eff_household
import com.lauzhack.twin.resources.eff_income_delta
import com.lauzhack.twin.resources.eff_income_factor
import com.lauzhack.twin.resources.eff_loan
import com.lauzhack.twin.resources.eff_market_rent
import com.lauzhack.twin.resources.eff_one_off
import com.lauzhack.twin.resources.eff_set_income
import com.lauzhack.twin.resources.explore_adds_up
import com.lauzhack.twin.resources.explore_all_months
import com.lauzhack.twin.resources.explore_change
import com.lauzhack.twin.resources.explore_close
import com.lauzhack.twin.resources.explore_decisions_line
import com.lauzhack.twin.resources.explore_financing_note
import com.lauzhack.twin.resources.explore_inv_note
import com.lauzhack.twin.resources.explore_life_change_note
import com.lauzhack.twin.resources.explore_line_absent
import com.lauzhack.twin.resources.explore_line_present
import com.lauzhack.twin.resources.explore_line_range
import com.lauzhack.twin.resources.explore_loan_left
import com.lauzhack.twin.resources.explore_loan_never
import com.lauzhack.twin.resources.explore_loan_payment
import com.lauzhack.twin.resources.explore_loan_rate
import com.lauzhack.twin.resources.explore_month_next
import com.lauzhack.twin.resources.explore_month_of
import com.lauzhack.twin.resources.explore_month_prev
import com.lauzhack.twin.resources.explore_month_value
import com.lauzhack.twin.resources.explore_no_debt
import com.lauzhack.twin.resources.explore_nw_formula
import com.lauzhack.twin.resources.explore_opening
import com.lauzhack.twin.resources.explore_scope_life
import com.lauzhack.twin.resources.explore_scope_turn
import com.lauzhack.twin.resources.explore_start
import com.lauzhack.twin.resources.explore_tap_hint
import com.lauzhack.twin.resources.explore_title
import com.lauzhack.twin.resources.explore_total
import com.lauzhack.twin.resources.explore_trend_FIXED
import com.lauzhack.twin.resources.explore_trend_PRICES
import com.lauzhack.twin.resources.explore_trend_RENT
import com.lauzhack.twin.resources.explore_trend_WAGES
import com.lauzhack.twin.resources.fig_debt
import com.lauzhack.twin.resources.fig_expenses
import com.lauzhack.twin.resources.fig_income
import com.lauzhack.twin.resources.fig_investments
import com.lauzhack.twin.resources.fig_loan_payments
import com.lauzhack.twin.resources.map_start
import com.lauzhack.twin.resources.map_when
import com.lauzhack.twin.resources.origin_note_ASSUMPTION
import com.lauzhack.twin.resources.row_debt_interest
import com.lauzhack.twin.resources.row_debt_new
import com.lauzhack.twin.resources.row_debt_paid
import com.lauzhack.twin.resources.state_cash
import com.lauzhack.twin.resources.state_net_worth
import com.lauzhack.twin.resources.trace_check_off
import com.lauzhack.twin.resources.trace_decision
import com.lauzhack.twin.resources.explore_cash_formula
import com.lauzhack.twin.resources.explore_origin
import com.lauzhack.twin.resources.trace_luck
import com.lauzhack.twin.resources.trace_origins
import com.lauzhack.twin.resources.trace_period
import com.lauzhack.twin.resources.trace_shock
import com.lauzhack.twin.resources.trace_sources
import com.lauzhack.twin.ui.Breakdown
import com.lauzhack.twin.ui.BreakdownRow
import com.lauzhack.twin.ui.Catalog
import com.lauzhack.twin.ui.Figure
import com.lauzhack.twin.ui.Inspect
import com.lauzhack.twin.ui.Origin
import com.lauzhack.twin.ui.RowKind
import com.lauzhack.twin.ui.Scope
import com.lauzhack.twin.ui.Span
import com.lauzhack.twin.ui.Trend
import com.lauzhack.twin.ui.TrendRates
import com.lauzhack.twin.ui.breakdown
import com.lauzhack.twin.ui.effectKey
import com.lauzhack.twin.ui.i18n.LocalLang
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.formatRate
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.i18n.localized
import com.lauzhack.twin.ui.lineStats
import com.lauzhack.twin.ui.monthsLeft
import com.lauzhack.twin.ui.pathTo
import com.lauzhack.twin.ui.rangeOf
import com.lauzhack.twin.ui.series
import com.lauzhack.twin.ui.spanOf
import com.lauzhack.twin.ui.startOffset
import com.lauzhack.twin.ui.theme.LocalTwinColors
import com.lauzhack.twin.ui.theme.Space
import com.lauzhack.twin.ui.theme.TwinIcons
import com.lauzhack.twin.ui.theme.figures
import com.lauzhack.twin.ui.trendOf
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Explore panel: "where does this number come from?". One figure of one moment of a life, the months behind it
 * and the short list of pieces that add up to it; every piece with a chevron opens the same view one level down
 * (balance -> flows -> origin), and the breadcrumbs go back up. A bar of the chart narrows everything to that month.
 *
 * Nothing is computed here: the rows come from [breakdown] over the engine's monthly statements ([months], null while
 * they are replayed), and the check at the bottom is the real residual of that sum.
 */
@Composable
fun ExplorePanel(
    inspect: Inspect,
    months: List<FinancialStatement>?,
    session: GameSession,
    catalog: Catalog,
    facts: FactCatalog,
    assumptions: Assumptions,
    trends: TrendRates?,
    onFigure: (Figure) -> Unit,
    onScope: (Scope) -> Unit,
    onMonth: (Int?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mpt = session.config.monthsPerTurn
    val lastMonth = session.turns.size * mpt
    Column(modifier) {
        // Header: what this is and which moment of the life it looks at.
        Row(Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.s, top = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.explore_title), style = MaterialTheme.typography.titleLarge)
                val now = session.turns.lastOrNull()?.stateAfter ?: session.initial
                Text(
                    if (lastMonth == 0) stringResource(Res.string.map_start) else stringResource(Res.string.map_when, lastMonth / 12, now.person.age),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) { Icon(TwinIcons.Close, stringResource(Res.string.explore_close), tint = MaterialTheme.colorScheme.onSurface) }
        }
        HorizontalDivider(Modifier.padding(top = Space.m), color = MaterialTheme.colorScheme.outlineVariant)

        if (months == null || months.size <= lastMonth) {
            Box(Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }
            return@Column
        }

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            val range = rangeOf(inspect.scope, lastMonth, mpt)
            val span = spanOf(inspect, lastMonth, mpt)
            val picked = inspect.month?.coerceIn(range.from, range.to)
            val data = breakdown(months, inspect.figure, span)

            Breadcrumbs(inspect.figure, onFigure)
            if (lastMonth > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Choice(stringResource(Res.string.explore_scope_turn), inspect.scope == Scope.TURN) { onScope(Scope.TURN) }
                    Choice(stringResource(Res.string.explore_scope_life), inspect.scope == Scope.LIFE) { onScope(Scope.LIFE) }
                }
            }

            Headline(data, months, picked)

            // Balances have a value at the first month of the range too; flows start the month after.
            val first = if (inspect.figure.isStock) range.from else range.from + 1
            val values = if (lastMonth > 0) series(months, inspect.figure, first, range.to) else emptyList()
            if (values.any { abs(it) >= 0.005 }) {       // nothing to draw (or to pick) when it never moves from zero
                MonthBars(
                    values = values,
                    firstMonth = first,
                    selected = picked,
                    decisionMonths = session.turns.map { it.index * mpt + 1 }.filter { it in first..range.to },   // the first month a decision counts
                    shockMonths = session.turns.mapNotNull { t -> t.shockMonth?.let { t.index * mpt + it + 1 } }.filter { it in first..range.to },
                    onSelect = onMonth,
                )
                MonthPicker(picked, range, months, onMonth)
            }

            // A single line or the financing cost is its own only row: the headline already says it.
            if (data.figure !is Figure.Line && data.figure != Figure.Financing) Rows(data, onFigure)

            Details(data, months, session, catalog, facts, assumptions, trends)
        }
    }
}

// ---------------------------------------------------------------------------------------------- navigation

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Breadcrumbs(figure: Figure, onFigure: (Figure) -> Unit) {
    val path = pathTo(figure)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        path.forEachIndexed { i, f ->
            val last = i == path.lastIndex
            Text(
                figureLabel(f),
                style = MaterialTheme.typography.labelLarge,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary,
                fontWeight = if (last) FontWeight.SemiBold else null,
                modifier = if (last) Modifier else Modifier.clip(RoundedCornerShape(3.dp)).pointerHoverIcon(PointerIcon.Hand).clickable { onFigure(f) },
            )
            if (!last) Text("›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One of two or three exclusive choices, as a quiet outlined chip. */
@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else scheme.outlineVariant, RoundedCornerShape(6.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Under the chart: the hint, or the picked month with previous / next and the way back to the whole period. */
@Composable
private fun MonthPicker(picked: Int?, range: Span, months: List<FinancialStatement>, onMonth: (Int?) -> Unit) {
    if (picked == null) {
        Text(stringResource(Res.string.explore_tap_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        IconButton(onClick = { onMonth(picked - 1) }, enabled = picked > range.from, modifier = Modifier.size(32.dp)) {
            Icon(TwinIcons.Back, stringResource(Res.string.explore_month_prev), modifier = Modifier.size(16.dp))
        }
        Text(monthLabel(months[picked]), style = MaterialTheme.typography.labelLarge.figures())
        IconButton(onClick = { onMonth(picked + 1) }, enabled = picked < range.to, modifier = Modifier.size(32.dp)) {
            Icon(TwinIcons.Forward, stringResource(Res.string.explore_month_next), modifier = Modifier.size(16.dp))
        }
        Box(Modifier.weight(1f))
        Choice(stringResource(Res.string.explore_all_months), selected = false) { onMonth(null) }
    }
}

/** "03/2027 · 22 years · month 27", or "Start" for the opening statement. */
@Composable
fun monthLabel(s: FinancialStatement): String {
    if (s.month == 0) return stringResource(Res.string.map_start)
    val (year, month) = s.period.take(7).split("-").let { it[0] to it[1] }
    return stringResource(Res.string.explore_month_value, "$month/$year", s.person.age, s.month)
}

// ---------------------------------------------------------------------------------------------- the figure

@Composable
private fun Headline(data: Breakdown, months: List<FinancialStatement>, picked: Int?) {
    val stock = data.figure.isStock
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Overline(
            when {
                data.span.to == 0 -> stringResource(Res.string.map_start)
                picked != null -> stringResource(Res.string.explore_month_of, monthLabel(months[data.span.to]))
                stock -> monthLabel(months[data.span.to])
                else -> stringResource(Res.string.trace_period, data.span.from, data.span.to)
            },
        )
        Text(
            formatChf(data.value, signed = !stock),
            style = MaterialTheme.typography.headlineMedium.figures(),
            color = if (data.value < 0) signColor(-1.0) else MaterialTheme.colorScheme.onSurface,
        )
        if (stock && data.span.months > 0 && data.figure != Figure.NetWorth) {
            Text(
                stringResource(Res.string.explore_change, formatChf(data.change, signed = true)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (data.figure == Figure.NetWorth && data.span.months > 0) {
            val before = months[data.span.from].let { it.cashChf + it.investmentsChf - (it.loan?.principalChf ?: 0.0) }
            Text(
                stringResource(Res.string.explore_change, formatChf(data.value - before, signed = true)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The pieces that add up to the figure. A piece with a chevron opens one level down. */
@Composable
private fun Rows(data: Breakdown, onFigure: (Figure) -> Unit) {
    if (data.rows.isEmpty()) return
    val scale = data.rows.filter { it.kind == RowKind.FLOW }.maxOfOrNull { abs(it.chf) } ?: 0.0
    val bars = data.figure == Figure.Income || data.figure == Figure.Expenses
    Column {
        data.rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // In the debt view more debt is bad news: the colours follow the person, not the sign.
            RowLine(row, if (bars && scale > 0) (abs(row.chf) / scale).toFloat() else null, good = if (data.figure == Figure.Debt) row.chf <= 0 else row.chf >= 0, onFigure)
        }
        if (data.rows.size > 1) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("= " + if (data.figure.isStock) figureLabel(data.figure) else stringResource(Res.string.explore_total),
                     style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(formatChf(data.value, signed = !data.figure.isStock), style = MaterialTheme.typography.bodyMedium.figures(), fontWeight = FontWeight.SemiBold)
            }
            // The real residual of the sum above, not a decoration.
            val ok = abs(data.residual) < 1.0
            Text(
                if (ok) stringResource(Res.string.explore_adds_up) else stringResource(Res.string.trace_check_off, formatChf(data.residual)),
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) LocalTwinColors.current.positive else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun RowLine(row: BreakdownRow, bar: Float?, good: Boolean, onFigure: (Figure) -> Unit) {
    val drill = row.drill
    val base = Modifier.fillMaxWidth()
    Column((if (drill != null) base.pointerHoverIcon(PointerIcon.Hand).clickable { onFigure(drill) } else base).padding(vertical = 10.dp),
           verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(
                rowLabel(row.id), style = MaterialTheme.typography.bodyMedium,
                color = if (row.kind == RowKind.START) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            row.origin?.let { OriginChip(it) }
            Text(
                formatChf(row.chf, signed = row.kind == RowKind.FLOW),
                style = MaterialTheme.typography.bodyMedium.figures(),
                color = when {
                    row.kind == RowKind.FLOW -> signColor(if (good) 1.0 else -1.0)
                    row.chf < 0 -> signColor(-1.0)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            // Same width with or without a chevron, so the amounts stay in one column.
            Text(if (drill != null) "›" else "", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary,
                 modifier = Modifier.size(width = 10.dp, height = 20.dp))
        }
        if (bar != null) ThinProgress(bar, color = signColor(if (good) 1.0 else -1.0).copy(alpha = 0.55f))
    }
}

// ---------------------------------------------------------------------------------------------- the origin

/** What the rows cannot say: the rule behind the figure, its sources and the decisions that set it. */
@Composable
private fun Details(
    data: Breakdown,
    months: List<FinancialStatement>,
    session: GameSession,
    catalog: Catalog,
    facts: FactCatalog,
    assumptions: Assumptions,
    trends: TrendRates?,
) {
    val span = data.span
    val mpt = session.config.monthsPerTurn
    val end = months[span.to]
    if (span.to == 0) {
        Note(stringResource(Res.string.explore_opening))
        if (data.figure == Figure.Debt) LoanFacts(end)
        return
    }
    // The decisions whose 24 months overlap the period.
    val turns = session.turns.filter { it.index * mpt < span.to && (it.index + 1) * mpt > span.from }
    when (val figure = data.figure) {
        Figure.NetWorth -> {
            Note(stringResource(Res.string.explore_nw_formula))
            if (turns.size == 1) DecisionOf(turns.single(), mpt, catalog, facts, only = null)
        }
        Figure.Cash -> {
            Note(stringResource(Res.string.explore_cash_formula))
            if (turns.size == 1) DecisionOf(turns.single(), mpt, catalog, facts, only = null)
        }
        Figure.Investments -> Note(stringResource(Res.string.explore_inv_note, formatRate(assumptions.investmentReturnAnnual)), Origin.ASSUMPTION)
        Figure.Debt -> LoanFacts(end)
        Figure.Financing -> Note(
            stringResource(Res.string.explore_financing_note, formatRate(assumptions.financingRateAnnual),
                           ((span.from + 1)..span.to).count { (months[it].cashFlow?.financingCostChf ?: 0.0) > 0 }, span.months),
            Origin.ASSUMPTION,
        )
        Figure.Income, Figure.Expenses -> OriginLegend(data.rows.mapNotNull { it.origin }.toSet() + Origin.PUBLIC, assumptions)
        is Figure.Line -> LineOrigin(figure, months, span, turns, mpt, catalog, facts, assumptions, trends)
    }
}

@Composable
private fun LineOrigin(
    line: Figure.Line,
    months: List<FinancialStatement>,
    span: Span,
    turns: List<Turn>,
    monthsPerTurn: Int,
    catalog: Catalog,
    facts: FactCatalog,
    assumptions: Assumptions,
    trends: TrendRates?,
) {
    val origin = com.lauzhack.twin.ui.originOf(line.key)
    val stats = lineStats(months, line, span)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Overline(stringResource(Res.string.explore_origin))
        if (stats.monthsPresent == 0) {
            Text(stringResource(Res.string.explore_line_absent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (stats.months > 1) {
            Text(stringResource(Res.string.explore_line_present, stats.monthsPresent, stats.months), style = MaterialTheme.typography.bodySmall)
            val first = stats.first
            val last = stats.last
            if (first != null && last != null && stats.monthsPresent > 1) {
                Text(stringResource(Res.string.explore_line_range, formatChf(abs(first)), formatChf(abs(last))), style = MaterialTheme.typography.bodySmall)
            }
        }
        OriginNote(origin, assumptions)
        // How it moves from month to month, with the public rate behind it.
        val trend = trendOf(line.key)
        val trendText = when (trend) {
            Trend.WAGES -> trends?.let { stringResource(Res.string.explore_trend_WAGES, formatRate(it.wages, 2)) }
            Trend.RENT -> trends?.let { stringResource(Res.string.explore_trend_RENT, formatRate(it.rent, 2)) }
            Trend.PRICES -> trends?.let { stringResource(Res.string.explore_trend_PRICES, formatRate(it.prices, 2)) }
            Trend.FIXED -> stringResource(Res.string.explore_trend_FIXED)
            Trend.NONE -> null
        }
        if (trendText != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                if (trend != Trend.FIXED) OriginChip(Origin.PUBLIC)      // a fixed amount is the decision's: its chip is just above
                Text(trendText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    // The decisions of the period that set this line directly.
    val touching = turns.filter { t -> effectsOf(t, catalog).any { effectKey(it.second) == line.key } }
    if (touching.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Overline(stringResource(Res.string.explore_decisions_line))
            touching.forEach { DecisionOf(it, monthsPerTurn, catalog, facts, only = line.key) }
        }
    } else if (origin == Origin.DATASET && turns.any { t -> effectsOf(t, catalog).any { effectKey(it.second) == null } }) {
        // A life change (a job, a city, studying) moves the learned flows without naming them.
        Note(stringResource(Res.string.explore_life_change_note))
    }
}

/** The effects of a turn as resolved at its luck: (the id of the action or shock, the effect, the month it starts counting from). */
private fun effectsOf(turn: Turn, catalog: Catalog): List<Triple<String, Effect, Int>> = buildList {
    catalog.action(turn.actionId)?.let { a -> resolve(a, turn.luck).effects.forEach { add(Triple(a.id, it, 0)) } }
    catalog.shock(turn.shockId)?.let { s -> resolve(s, turn.shockLuck ?: 0.5).effects.forEach { add(Triple(s.id, it, turn.shockMonth ?: 0)) } }
}

/** What a decision (and the shock of its turn) set: the luck, the drawn values, each effect and the sources. [only] keeps the effects on one line. */
@Composable
private fun DecisionOf(turn: Turn, monthsPerTurn: Int, catalog: Catalog, facts: FactCatalog, only: String?) {
    val turnStart = turn.index * monthsPerTurn
    val action = catalog.action(turn.actionId)
    if (action != null && action.effects.isNotEmpty()) {
        val effects = resolve(action, turn.luck).effects.filter { only == null || effectKey(it) == only }
        if (effects.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.trace_decision, localized(action.title)), style = MaterialTheme.typography.titleSmall)
            if (action.uncertain.isNotEmpty()) {
                Text(stringResource(Res.string.trace_luck, (turn.luck * 100).roundToInt(), drawnText(action.uncertain, turn.values)), style = MaterialTheme.typography.bodySmall)
            }
            effects.mapNotNull { effectText(it, turnStart, facts) }.forEach { Bullet(it) }
            if (action.sources.isNotEmpty()) {
                Overline(stringResource(Res.string.trace_sources))
                action.sources.forEach { Bullet(it, small = true) }
            }
        }
    }
    val shock = catalog.shock(turn.shockId)
    if (shock != null) {
        val effects = resolve(shock, turn.shockLuck ?: 0.5).effects.filter { only == null || effectKey(it) == only }
        if (effects.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.trace_shock, localized(shock.title)), style = MaterialTheme.typography.titleSmall)
            if (shock.uncertain.isNotEmpty()) {
                Text(stringResource(Res.string.trace_luck, ((turn.shockLuck ?: 0.5) * 100).roundToInt(), drawnText(shock.uncertain, turn.shockValues)), style = MaterialTheme.typography.bodySmall)
            }
            effects.mapNotNull { effectText(it, turnStart + (turn.shockMonth ?: 0), facts) }.forEach { Bullet(it) }
            if (shock.sources.isNotEmpty()) {
                Overline(stringResource(Res.string.trace_sources))
                shock.sources.forEach { Bullet(it, small = true) }
            }
        }
    }
}

/** The loan at one moment: rate, payment and how long is left. */
@Composable
private fun LoanFacts(s: FinancialStatement) {
    val loan = s.loan?.takeIf { it.principalChf > 0 }
    if (loan == null) {
        Note(stringResource(Res.string.explore_no_debt))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        LabeledValue(stringResource(Res.string.explore_loan_rate), formatRate(loan.annualRate, 2))
        LabeledValue(stringResource(Res.string.explore_loan_payment), formatChf(loan.monthlyPaymentChf))
        val left = monthsLeft(loan)
        if (left != null) LabeledValue(stringResource(Res.string.explore_loan_left), left.toString())
        else Note(stringResource(Res.string.explore_loan_never))
    }
}

@Composable
private fun OriginLegend(shown: Set<Origin>, assumptions: Assumptions) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Overline(stringResource(Res.string.trace_origins))
        Origin.entries.filter { it in shown }.forEach { OriginNote(it, assumptions) }
    }
}

@Composable
private fun OriginNote(origin: Origin, assumptions: Assumptions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        OriginChip(origin)
        Text(
            if (origin == Origin.ASSUMPTION) stringResource(Res.string.origin_note_ASSUMPTION, formatRate(assumptions.financingRateAnnual), formatRate(assumptions.investmentReturnAnnual))
            else keyString("origin_note_${origin.name}"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Note(text: String, origin: Origin? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        origin?.let { OriginChip(it) }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Bullet(text: String, small: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", color = MaterialTheme.colorScheme.secondary)
        Text(text, style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------------------- labels

@Composable
fun originColor(origin: Origin): Color = when (origin) {
    Origin.DATASET -> MaterialTheme.colorScheme.secondary
    Origin.PUBLIC -> LocalTwinColors.current.alternative
    Origin.EVENT -> LocalTwinColors.current.chosen
    Origin.ASSUMPTION -> MaterialTheme.colorScheme.tertiary
}

@Composable
fun OriginChip(origin: Origin) {
    val color = originColor(origin)
    Text(
        keyString("origin_${origin.name}"),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun figureLabel(figure: Figure): String = when (figure) {
    Figure.NetWorth -> stringResource(Res.string.state_net_worth)
    Figure.Cash -> stringResource(Res.string.state_cash)
    Figure.Investments -> stringResource(Res.string.fig_investments)
    Figure.Debt -> stringResource(Res.string.fig_debt)
    Figure.Income -> stringResource(Res.string.fig_income)
    Figure.Expenses -> stringResource(Res.string.fig_expenses)
    Figure.Financing -> stringResource(Res.string.driver_financing)
    is Figure.Line -> categoryLabel(figure.key)
}

/** Label of a row of a breakdown: a part, a kind of flow, or a category. */
@Composable
private fun rowLabel(id: String): String = when (id) {
    "start" -> stringResource(Res.string.explore_start)
    "cash" -> stringResource(Res.string.state_cash)
    "investments" -> stringResource(Res.string.fig_investments)
    "debt" -> stringResource(Res.string.fig_debt)
    "income" -> stringResource(Res.string.fig_income)
    "expenses" -> stringResource(Res.string.fig_expenses)
    "loan_payments" -> stringResource(Res.string.fig_loan_payments)
    "financing" -> stringResource(Res.string.driver_financing)
    "debt_new" -> stringResource(Res.string.row_debt_new)
    "debt_interest" -> stringResource(Res.string.row_debt_interest)
    "debt_paid" -> stringResource(Res.string.row_debt_paid)
    else -> categoryLabel(id)
}

/** Label of a flow key, in the UI language: "income:salary" -> "Salary", "expense:TUITION" -> "Tuition (decision)". */
@Composable
fun categoryLabel(key: String): String {
    val name = key.substringAfter(':')
    return when {
        key == "loan_interest" -> stringResource(Res.string.driver_debt)
        key == "financing_cost" -> stringResource(Res.string.driver_financing)
        key == "investment_growth" -> stringResource(Res.string.driver_investment)
        name.isNotEmpty() && name == name.uppercase() -> keyString("cat_event_$name")
        else -> keyString("cat_$name").let { if (it == "cat_$name") name.replace('_', ' ') else it }
    }
}

/** One effect of a decision in plain words, with absolute months. */
@Composable
private fun effectText(effect: Effect, turnStart: Int, facts: FactCatalog): String? {
    val from = turnStart + effect.startOffset
    @Composable fun duration(months: Int?) = months?.let { stringResource(Res.string.eff_duration, it) }.orEmpty()
    return when (effect) {
        is Effect.SetIncome -> stringResource(Res.string.eff_set_income, formatChf(effect.monthlyChf), from)
        is Effect.IncomeDelta -> stringResource(Res.string.eff_income_delta, formatChf(effect.deltaChf, signed = true), from) + duration(effect.months)
        is Effect.IncomeFactor -> stringResource(Res.string.eff_income_factor, ((effect.factor * 1000).roundToInt() / 1000.0).toString(), from) + duration(effect.months)
        is Effect.ExpenseDelta -> stringResource(Res.string.eff_expense, keyString("cat_event_${effect.category.name}"), formatChf(effect.deltaChf), from) + duration(effect.months)   // an expense: no "+"
        is Effect.OneOff -> stringResource(Res.string.eff_one_off, formatChf(effect.chf), from)
        is Effect.AddLiability -> stringResource(Res.string.eff_loan, formatChf(effect.principalChf), formatRate(effect.annualRate), effect.termMonths)
        is Effect.MarketRent -> stringResource(Res.string.eff_market_rent, (effect.quantile * 100).roundToInt(), from)
        is Effect.SetHousehold -> stringResource(Res.string.eff_household, effect.household.name.lowercase().replace('_', ' '), from)
        is Effect.SetEmployment -> stringResource(Res.string.eff_employment, keyString("employment_${effect.employment.name}"), from)
        is Effect.SetFact -> stringResource(Res.string.eff_fact, facts.facts[effect.key]?.get(effect.value)?.let { localized(it) } ?: effect.value, from)
        is Effect.AddFlag -> stringResource(Res.string.eff_flag, facts.flags[effect.flag]?.let { localized(it) } ?: effect.flag)
    }
}

/** "Monthly salary 6’850, Tuition per month 320": the values drawn for a decision, with the labels of the dilemma. */
@Composable
private fun drawnText(uncertain: List<Uncertain>, values: Map<String, Double>): String {
    val lang = LocalLang.current
    return uncertain.mapNotNull { u -> values[u.name]?.let { keyString("uncertain_${u.name}") + " " + formatValue(u.format, it, lang) } }.joinToString(", ")
}
