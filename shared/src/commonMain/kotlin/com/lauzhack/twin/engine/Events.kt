package com.lauzhack.twin.engine

import com.lauzhack.twin.model.Action
import com.lauzhack.twin.model.Better
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.Law
import com.lauzhack.twin.model.LocalizedText
import com.lauzhack.twin.model.Loan
import com.lauzhack.twin.model.PendingChange
import com.lauzhack.twin.model.Requirements
import com.lauzhack.twin.model.Shock
import com.lauzhack.twin.model.Uncertain
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sqrt

// Actions and shocks change fields of the statement; the simulator moves time (docs/decision-tree.md). Python
// reference: tools/game.py.

/** The effects of an outcome as pending changes, at the start of the turn (the statement's month) plus their
 *  offset. A change that lasts `months` also schedules its reversal. */
fun applyEffects(s: FinancialStatement, effects: List<Effect>, eventId: String): FinancialStatement {
    val t0 = s.month
    val pending = s.pendingChanges.toMutableList()
    fun add(month: Int, change: PendingChange.() -> PendingChange) {
        pending += PendingChange(month = month, sourceEventId = eventId).change()
    }
    for (eff in effects) {
        when (eff) {
            is Effect.SetIncome -> add(t0 + eff.startOffsetMonths) { copy(monthlyIncomeChf = eff.monthlyChf) }
            is Effect.IncomeFactor -> {
                val start = t0 + eff.startOffsetMonths
                add(start) { copy(incomeFactor = eff.factor) }
                eff.months?.let { add(start + it) { copy(incomeFactor = 1 / eff.factor) } }
            }
            is Effect.SetHousehold -> add(t0 + eff.startOffsetMonths) { copy(household = eff.household) }
            is Effect.MarketRent -> add(t0 + eff.startOffsetMonths) { copy(marketRentQuantile = eff.quantile) }
            is Effect.IncomeDelta -> {
                val start = t0 + eff.startOffsetMonths
                add(start) { copy(incomeDeltaChf = eff.deltaChf) }
                eff.months?.let { add(start + it) { copy(incomeDeltaChf = -eff.deltaChf) } }
            }
            is Effect.ExpenseDelta -> {
                val start = t0 + eff.startOffsetMonths
                add(start) { copy(expenseCategory = eff.category, expenseDeltaChf = eff.deltaChf) }
                eff.months?.let { add(start + it) { copy(expenseCategory = eff.category, expenseDeltaChf = -eff.deltaChf) } }
            }
            is Effect.OneOff -> {                   // one month only (negative = a one-time gain)
                val start = t0 + eff.startOffsetMonths
                add(start) { copy(expenseCategory = ExpenseCategory.OTHER, expenseDeltaChf = eff.chf) }
                add(start + 1) { copy(expenseCategory = ExpenseCategory.OTHER, expenseDeltaChf = -eff.chf) }
            }
            is Effect.AddLiability -> add(t0) {
                copy(loan = Loan(principalChf = eff.principalChf, annualRate = eff.annualRate,
                                 monthlyPaymentChf = round2(annuityPayment(eff.principalChf, eff.annualRate, eff.termMonths))))
            }
            is Effect.SetEmployment -> add(t0 + eff.startOffsetMonths) { copy(employment = eff.employment) }
            is Effect.SetFact -> add(t0 + eff.startOffsetMonths) { copy(factKey = eff.key, factValue = eff.value) }
            is Effect.AddFlag -> add(t0 + eff.startOffsetMonths) { copy(flag = eff.flag) }
        }
    }
    return s.copy(pendingChanges = pending)
}

/** Life changes due at the start of the turn already count for the actions available (docs/decision-tree.md).
 *  Money changes stay pending: the simulator applies them in the month's step. */
fun applyDueLifeChanges(s: FinancialStatement): FinancialStatement {
    var person = s.person
    val still = mutableListOf<PendingChange>()
    for (ch in s.pendingChanges) {
        when {
            ch.month > s.month -> still += ch
            ch.employment != null -> person = person.copy(employment = ch.employment)
            ch.factKey != null -> person = person.copy(facts = person.facts + (ch.factKey to ch.factValue!!))
            ch.flag != null -> if (ch.flag !in person.flags) person = person.copy(flags = person.flags + ch.flag)
            ch.household != null -> person = person.copy(household = ch.household)
            else -> still += ch
        }
    }
    return s.copy(person = person, pendingChanges = still)
}

/** Do [req] match the statement? */
fun matches(req: Requirements?, s: FinancialStatement): Boolean {
    if (req == null) return true
    val p = s.person
    return (req.employment == null || p.employment in req.employment) &&
        (req.household == null || p.household in req.household) &&
        (req.minAge == null || p.age >= req.minAge) &&
        (req.maxAge == null || p.age <= req.maxAge) &&
        (req.facts ?: emptyMap()).all { (k, values) -> p.facts[k] in values } &&
        (req.flagsAll ?: emptyList()).all { it in p.flags } &&
        (req.flagsNone ?: emptyList()).none { it in p.flags } &&
        (req.minCashChf == null || s.cashChf >= req.minCashChf) &&
        (req.maxCashChf == null || s.cashChf <= req.maxCashChf)
}

/** Can the player take [action] at the start of [turn]? [used] = actions already taken on this path of the tree. */
fun isEligible(action: Action, s: FinancialStatement, turn: Int, used: Set<String>): Boolean =
    turn >= action.earliestTurn && (action.repeatable || action.id !in used) && matches(action.requires, s)

/** The actions available now, in file order (NOTHING always: no requirements). */
fun actionsAt(actions: List<Action>, s: FinancialStatement, turn: Int, used: Set<String>): List<Action> =
    actions.filter { isEligible(it, s, turn, used) }

/** The effects starting [months] later (a shock in the middle of a turn). */
fun shifted(effects: List<Effect>, months: Int): List<Effect> = effects.map { e ->
    when (e) {
        is Effect.SetIncome -> e.copy(startOffsetMonths = e.startOffsetMonths + months)
        is Effect.IncomeDelta -> e.copy(startOffsetMonths = e.startOffsetMonths + months)
        is Effect.IncomeFactor -> e.copy(startOffsetMonths = e.startOffsetMonths + months)
        is Effect.SetHousehold -> e.copy(startOffsetMonths = e.startOffsetMonths + months)
        is Effect.MarketRent -> e.copy(startOffsetMonths = e.startOffsetMonths + months)
        is Effect.ExpenseDelta -> e.copy(startOffsetMonths = e.startOffsetMonths + months)
        is Effect.OneOff -> e.copy(startOffsetMonths = e.startOffsetMonths + months)
        is Effect.AddLiability -> e
        is Effect.SetEmployment -> e.copy(startOffsetMonths = e.startOffsetMonths + months)
        is Effect.SetFact -> e.copy(startOffsetMonths = e.startOffsetMonths + months)
        is Effect.AddFlag -> e.copy(startOffsetMonths = e.startOffsetMonths + months)
    }
}

/** A shock drawn for a turn: which one, its luck (the percentile of its uncertain numbers) and the month it starts. */
data class DrawnShock(val shock: Shock, val luck: Double, val month: Int)

/** The involuntary event of this turn, or null: the first shock whose requirements match [s] (the statement after the
 *  action's immediate changes) happens with its probability. Luck from the node's seed (see [PortableRandom.nodeSeed]). */
fun drawShock(shocks: List<Shock>, s: FinancialStatement, turn: Int, nodeSeed: Long, monthsPerTurn: Int): DrawnShock? {
    val shock = shocks.firstOrNull { matches(it.requires, s) } ?: return null
    if (PortableRandom.unit(nodeSeed, turn, PortableRandom.SHOCK_SALT) >= shock.probability) return null
    val month = min((PortableRandom.unit(nodeSeed, turn, PortableRandom.SHOCK_MONTH_SALT) * monthsPerTurn).toInt(), monthsPerTurn - 1)
    return DrawnShock(shock, PortableRandom.unit(nodeSeed, turn, PortableRandom.SHOCK_LUCK_SALT), month)
}

// ---------------------------------------------------------------------------------------------- distributions

/** Percentiles beyond p5 / p95 are not drawn (no absurd tails). */
const val LUCK_MIN = 0.05
const val LUCK_MAX = 0.95

private val A = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
                              -3.066479806614716e+01, 2.506628277459239e+00)
private val B = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01,
                              -1.328068155288572e+01)
private val C = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00,
                              4.374664141464968e+00, 2.938163982698783e+00)
private val D = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)

/** Inverse of the standard normal CDF (Acklam's rational approximation; tools/game.py `probit`). */
fun probit(p: Double): Double {
    if (p < 0.02425) {
        val q = sqrt(-2 * ln(p))
        return (((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5]) /
            ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1)
    }
    if (p > 1 - 0.02425) {
        val q = sqrt(-2 * ln(1 - p))
        return -(((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5]) /
            ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1)
    }
    val q = p - 0.5
    val r = q * q
    return (((((A[0] * r + A[1]) * r + A[2]) * r + A[3]) * r + A[4]) * r + A[5]) * q /
        (((((B[0] * r + B[1]) * r + B[2]) * r + B[3]) * r + B[4]) * r + 1)
}

/**
 * The value of an uncertain number at the percentile [luck] (high luck = the [Uncertain.better] side). A two-piece normal:
 * below the median the spread comes from the low quantile, above it from the high one, so it goes exactly through the
 * three values of the data. NORMAL on the value, LOGNORMAL on its logarithm, QUANTILE = the percentile itself.
 * Clamped to [min, max] and rounded to `step`. Python reference: tools/game.py `draw`.
 */
fun draw(spec: Uncertain, luck: Double): Double {
    var p = min(max(luck, LUCK_MIN), LUCK_MAX)
    if (spec.better == Better.LOW) p = 1 - p
    var v = if (spec.law == Law.QUANTILE) {
        min(max(p, spec.low.value), spec.high.value)
    } else {
        val log = spec.law == Law.LOGNORMAL
        fun f(x: Double) = if (log) ln(x) else x
        fun finv(x: Double) = if (log) exp(x) else x
        val z = probit(p)
        val sigma = if (z < 0) (f(spec.median) - f(spec.low.value)) / -probit(spec.low.q)
                    else (f(spec.high.value) - f(spec.median)) / probit(spec.high.q)
        finv(f(spec.median) + sigma * z)
    }
    spec.min?.let { v = max(v, it) }
    spec.max?.let { v = min(v, it) }
    return round(v / spec.step) * spec.step
}

/** An outcome at this luck: its effects (every uncertain number drawn at the same percentile) and the drawn values. */
data class Resolved(val effects: List<Effect>, val values: Map<String, Double>)

fun resolve(effects: List<Effect>, uncertain: List<Uncertain>, luck: Double): Resolved {
    val out = effects.toMutableList()
    val values = linkedMapOf<String, Double>()
    for (spec in uncertain) {
        val v = draw(spec, luck)
        values[spec.name] = v
        for (t in spec.targets) out[t.effect] = out[t.effect].with(t.field, v)
    }
    return Resolved(out, values)
}

fun resolve(action: Action, luck: Double): Resolved = resolve(action.effects, action.uncertain, luck)
fun resolve(shock: Shock, luck: Double): Resolved = resolve(shock.effects, shock.uncertain, luck)

/** [this] effect with its numeric [field] set to [v] (months as whole numbers). */
fun Effect.with(field: String, v: Double): Effect {
    val months = round(v).toInt()
    fun bad(): Nothing = throw IllegalArgumentException("${this::class.simpleName} has no numeric field $field")
    return when (this) {
        is Effect.SetIncome -> when (field) { "monthlyChf" -> copy(monthlyChf = v); "startOffsetMonths" -> copy(startOffsetMonths = months); else -> bad() }
        is Effect.IncomeDelta -> when (field) { "deltaChf" -> copy(deltaChf = v); "startOffsetMonths" -> copy(startOffsetMonths = months); "months" -> copy(months = months); else -> bad() }
        is Effect.IncomeFactor -> when (field) { "factor" -> copy(factor = v); "startOffsetMonths" -> copy(startOffsetMonths = months); "months" -> copy(months = months); else -> bad() }
        is Effect.SetHousehold -> when (field) { "startOffsetMonths" -> copy(startOffsetMonths = months); else -> bad() }
        is Effect.MarketRent -> when (field) { "quantile" -> copy(quantile = v); "startOffsetMonths" -> copy(startOffsetMonths = months); else -> bad() }
        is Effect.ExpenseDelta -> when (field) { "deltaChf" -> copy(deltaChf = v); "startOffsetMonths" -> copy(startOffsetMonths = months); "months" -> copy(months = months); else -> bad() }
        is Effect.OneOff -> when (field) { "chf" -> copy(chf = v); "startOffsetMonths" -> copy(startOffsetMonths = months); else -> bad() }
        is Effect.AddLiability -> when (field) { "principalChf" -> copy(principalChf = v); "annualRate" -> copy(annualRate = v); "termMonths" -> copy(termMonths = months); else -> bad() }
        is Effect.SetEmployment -> when (field) { "startOffsetMonths" -> copy(startOffsetMonths = months); else -> bad() }
        is Effect.SetFact -> when (field) { "startOffsetMonths" -> copy(startOffsetMonths = months); else -> bad() }
        is Effect.AddFlag -> when (field) { "startOffsetMonths" -> copy(startOffsetMonths = months); else -> bad() }
    }
}

/** How a drawn value is written in a story beat: 6100.0 -> "6100", 1.2995 -> "1.2995" (tools/game.py `number_text`). */
fun numberText(x: Double): String = if (x == floor(x) && abs(x) < 1e15) x.toLong().toString() else x.toString()

private val FLAT_WORDS = mapOf(
    Lang.EN to listOf("cheaper than usual", "at the usual price", "more expensive than usual"),
    Lang.ES to listOf("más barato de lo normal", "a precio normal", "más caro de lo normal"),
)

/** A drawn value as the story tells it (tools/game.py `format_value`). */
fun formatValue(format: String, v: Double, lang: Lang): String = when (format) {
    "chf" -> v.roundToLong().let { n -> (if (n < 0) "-" else "") + abs(n).toString().reversed().chunked(3).joinToString("’").reversed() }
    "change" -> round((v - 1) * 100).toLong().let { r -> (if (r >= 0) "+" else "-") + abs(r) + " %" }
    "share" -> "${round(v * 100).toLong()} %"
    "percent" -> (round(v * 1000) / 10).let { x -> val s = "${x.toLong()}.${abs(round(x * 10).toLong() % 10)}"; (if (lang == Lang.ES) s.replace('.', ',') else s) + " %" }
    "months" -> round(v).toLong().toString()
    "flat" -> FLAT_WORDS.getValue(lang)[if (v < 0.35) 0 else if (v > 0.65) 2 else 1]
    else -> numberText(v)
}

/** The outcome sentence of an action or shock with its drawn values. */
fun outcomeText(outcome: LocalizedText, uncertain: List<Uncertain>, values: Map<String, Double>, lang: Lang): String {
    var text = outcome[lang] ?: outcome.values.firstOrNull().orEmpty()
    for (spec in uncertain) values[spec.name]?.let { text = text.replace("{${spec.name}}", formatValue(spec.format, it, lang)) }
    return text
}
