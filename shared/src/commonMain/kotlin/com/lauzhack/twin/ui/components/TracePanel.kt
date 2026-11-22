package com.lauzhack.twin.ui.components

import com.lauzhack.twin.ui.theme.figures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.ui.Catalog
import com.lauzhack.twin.resources.trace_shock
import com.lauzhack.twin.model.FactCatalog
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
import com.lauzhack.twin.resources.origin_note_ASSUMPTION
import com.lauzhack.twin.resources.trace_luck
import com.lauzhack.twin.engine.formatValue
import com.lauzhack.twin.engine.resolve
import com.lauzhack.twin.model.Uncertain
import com.lauzhack.twin.ui.i18n.LocalLang
import com.lauzhack.twin.resources.trace_check_off
import com.lauzhack.twin.resources.trace_check_ok
import com.lauzhack.twin.resources.trace_close
import com.lauzhack.twin.resources.trace_decision
import com.lauzhack.twin.resources.trace_end
import com.lauzhack.twin.resources.trace_formula
import com.lauzhack.twin.resources.trace_open
import com.lauzhack.twin.resources.trace_origins
import com.lauzhack.twin.resources.trace_period
import com.lauzhack.twin.resources.trace_sources
import com.lauzhack.twin.resources.trace_start
import com.lauzhack.twin.ui.Origin
import com.lauzhack.twin.ui.Trace
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.formatPercent
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.i18n.localized
import com.lauzhack.twin.ui.startOffset
import com.lauzhack.twin.ui.theme.LocalTwinColors
import com.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Where do these numbers come from?": a collapsible explanation of a period of a life. Start net worth, every
 * category of money in and out with its origin (dataset, public data, decision, assumption), end net worth and the
 * check that it adds up; for a single turn, also what the decision set (the drawn values and effects) and the sources.
 * Nothing is computed here: [trace] comes from the engine's attribution (ui/Trace.kt).
 */
@Composable
fun TracePanel(
    trace: Trace,
    catalog: Catalog,
    facts: FactCatalog,
    assumptions: Assumptions,
    initiallyOpen: Boolean = false,
) {
    var open by remember(trace) { mutableStateOf(initiallyOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
            Icon(TwinIcons.Info, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (open) Res.string.trace_close else Res.string.trace_open))
        }
        if (open) TraceBody(trace, catalog, facts, assumptions)
    }
}

@Composable
private fun TraceBody(trace: Trace, catalog: Catalog, facts: FactCatalog, assumptions: Assumptions) {
    SurfaceCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
        Overline(stringResource(Res.string.trace_period, trace.fromMonth, trace.toMonth))

        // The equation: start + lines = end.
        AmountRow(stringResource(Res.string.trace_start), formatChf(trace.startNetWorth), bold = true)
        trace.lines.forEach { line ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(categoryLabel(line.key), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OriginChip(line.origin)
                Text(formatChf(line.chf, signed = true), style = MaterialTheme.typography.labelMedium.figures(), color = signColor(line.chf))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AmountRow(stringResource(Res.string.trace_end), formatChf(trace.endNetWorth), bold = true)
        Text(
            if (abs(trace.residual) < 1.0) stringResource(Res.string.trace_check_ok) else stringResource(Res.string.trace_check_off, formatChf(trace.residual)),
            style = MaterialTheme.typography.labelSmall,
            color = if (abs(trace.residual) < 1.0) LocalTwinColors.current.positive else MaterialTheme.colorScheme.tertiary,
        )
        Text(stringResource(Res.string.trace_formula), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // For one turn: what the decision set, and where its numbers come from.
        if (trace.turns.size == 1) {
            val turn = trace.turns.single()
            val action = catalog.action(turn.actionId)
            if (action != null && action.effects.isNotEmpty()) {
                val outcome = resolve(action, turn.luck)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(Res.string.trace_decision, localized(action.title)), style = MaterialTheme.typography.titleSmall)
                if (action.uncertain.isNotEmpty()) {
                    Text(stringResource(Res.string.trace_luck, kotlin.math.round(turn.luck * 100).toInt(), drawnText(action.uncertain, turn.values)),
                         style = MaterialTheme.typography.bodySmall)
                }
                outcome.effects.mapNotNull { effectText(it, trace.fromMonth, facts) }.forEach { Bullet(it) }
                if (action.sources.isNotEmpty()) {
                    Overline(stringResource(Res.string.trace_sources))
                    action.sources.forEach { Bullet(it, small = true) }
                }
            }
            val shock = catalog.shock(turn.shockId)
            if (shock != null) {
                val outcome = resolve(shock, turn.shockLuck ?: 0.5)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(Res.string.trace_shock, localized(shock.title)), style = MaterialTheme.typography.titleSmall)
                if (shock.uncertain.isNotEmpty()) {
                    Text(stringResource(Res.string.trace_luck, kotlin.math.round((turn.shockLuck ?: 0.5) * 100).toInt(), drawnText(shock.uncertain, turn.shockValues)),
                         style = MaterialTheme.typography.bodySmall)
                }
                outcome.effects.mapNotNull { effectText(it, trace.fromMonth + (turn.shockMonth ?: 0), facts) }.forEach { Bullet(it) }
                if (shock.sources.isNotEmpty()) {
                    Overline(stringResource(Res.string.trace_sources))
                    shock.sources.forEach { Bullet(it, small = true) }
                }
            }
        }

        // Legend of the origins that appear.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Overline(stringResource(Res.string.trace_origins))
        val shown = (trace.lines.map { it.origin } + Origin.PUBLIC).toSet()
        Origin.entries.filter { it in shown }.forEach { origin ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                OriginChip(origin)
                Text(
                    if (origin == Origin.ASSUMPTION) stringResource(Res.string.origin_note_ASSUMPTION, rate(assumptions.financingRateAnnual), rate(assumptions.investmentReturnAnnual))
                    else keyString("origin_note_${origin.name}"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else null, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.figures(), fontWeight = if (bold) FontWeight.SemiBold else null)
    }
}

@Composable
private fun Bullet(text: String, small: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", color = MaterialTheme.colorScheme.secondary)
        Text(text, style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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

/** Label of an attribution key, in the UI language: "income:salary" -> "Salary", "expense:TUITION" -> "Tuition (decision)". */
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

/** One effect of a case in plain words, with absolute months. */
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
        is Effect.AddLiability -> stringResource(Res.string.eff_loan, formatChf(effect.principalChf), rate(effect.annualRate), effect.termMonths)
        is Effect.MarketRent -> stringResource(Res.string.eff_market_rent, (effect.quantile * 100).roundToInt(), from)
        is Effect.SetHousehold -> stringResource(Res.string.eff_household, effect.household.name.lowercase().replace('_', ' '), from)
        is Effect.SetEmployment -> stringResource(Res.string.eff_employment, keyString("employment_${effect.employment.name}"), from)
        is Effect.SetFact -> stringResource(Res.string.eff_fact, facts.facts[effect.key]?.get(effect.value)?.let { localized(it) } ?: effect.value, from)
        is Effect.AddFlag -> stringResource(Res.string.eff_flag, facts.flags[effect.flag]?.let { localized(it) } ?: effect.flag)
    }
}

/** An annual rate with one decimal ("8.5 %"): formatPercent rounds to whole percents. */
private fun rate(p: Double): String {
    val tenths = (p * 1000).roundToInt()
    return "${tenths / 10}.${abs(tenths % 10)} %"
}

/** "Monthly salary 6’850, Tuition per month 320": the values drawn for a decision, with the labels of the dilemma. */
@Composable
private fun drawnText(uncertain: List<Uncertain>, values: Map<String, Double>): String {
    val lang = LocalLang.current
    return uncertain.mapNotNull { u -> values[u.name]?.let { keyString("uncertain_${u.name}") + " " + formatValue(u.format, it, lang) } }.joinToString(", ")
}
