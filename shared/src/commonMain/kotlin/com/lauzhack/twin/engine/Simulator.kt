package com.lauzhack.twin.engine

import com.lauzhack.twin.model.CareerCurve
import com.lauzhack.twin.model.CareerCurves
import com.lauzhack.twin.model.CashFlow
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.FlowBasis
import com.lauzhack.twin.model.FlowDirection
import com.lauzhack.twin.model.FlowModel
import com.lauzhack.twin.model.FlowSpec
import com.lauzhack.twin.model.Loan
import com.lauzhack.twin.model.MacroFile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

// FinancialStatement(t) -> FinancialStatement(t+1) (docs/simulator.md). Python reference: tools/simulate.py.
//   0. changes decided by events that are due        1. monthly trends (macro.json)
//   2. the month's flows from the flow model         3. loan, financing, investments
//   4. accounting: cash(t+1) = cash(t) + net
// The model never writes cash directly: it only produces the month's flows.

/** Engine rules that are not in the data (assumptions, until content/assumptions.json exists). */
data class EngineRules(
    val financingRateAnnual: Double = 0.085,     // interest on negative cash = max rate in synthetic-liabilities.csv
    val investmentReturnAnnual: Double = 0.0,    // no returns in the dataset
)

enum class SimMode { EXPECTED, SAMPLE }

private val WORKING = setOf(Employment.EMPLOYED, Employment.PART_TIME, Employment.SELF_EMPLOYED)

class Simulator(
    val model: FlowModel,
    val macro: MacroFile?,
    val rules: EngineRules = EngineRules(),
) {
    private val priceInflation = macro?.trends?.priceInflationAnnual?.value ?: 0.0
    private val wageGrowth = macro?.trends?.wageGrowthAnnual?.value ?: 0.0
    private val rentGrowth = macro?.trends?.rentGrowthSittingTenantAnnual?.value ?: 0.0
    private val careerCurves: CareerCurves? = macro?.trends?.careerCurveByAge?.value

    // ------------------------------------------------------------------------------------------ features

    /** Every field that describes the person: categorical fields and facts, numeric fields as bands. */
    fun features(s: FinancialStatement): Map<String, String> {
        val facts = s.person.facts
        val education = facts[FactKey.EDUCATION] ?: "UNKNOWN"
        val city = facts[FactKey.CITY]
        val numbers = mapOf(
            "age" to s.person.age.toDouble(),
            "income" to s.monthlyIncomeChf,
            "rent" to s.monthlyRentChf,
            "cash" to s.cashChf,
            "debt" to (s.loan?.principalChf ?: 0.0),
            "investments" to s.investmentsChf,
        )
        val out = linkedMapOf(
            "studying" to if (education.endsWith("_IN_PROGRESS")) "YES" else "NO",
            "employment" to s.person.employment.name,
            "canton" to (city?.let { model.cityCanton[it] } ?: "UNKNOWN"),
            "household" to s.person.household.name,
        )
        for ((feature, field) in model.meta.numericFeatures) {
            out[feature] = bandLabel(numbers[field] ?: 0.0, model.meta.bandEdges[field])
        }
        out["city"] = city ?: "UNKNOWN"
        out["education"] = education
        out["occupation"] = facts[FactKey.OCCUPATION] ?: "UNKNOWN"
        out["housing"] = facts[FactKey.HOUSING] ?: "UNKNOWN"
        out["family"] = facts[FactKey.FAMILY] ?: "UNKNOWN"
        return out
    }

    // ------------------------------------------------------------------------------------------ flows

    private fun flowAmount(flow: FlowSpec, feats: Map<String, String>, mode: SimMode, rng: Random?): Double {
        val p = min(predictRaw(flow.probability.base, flow.probability.factors, flow.probability.unknown, feats), 1.0)
        val a = predictRaw(flow.amount.base, flow.amount.factors, flow.amount.unknown, feats)
        if (mode == SimMode.EXPECTED) return p * a
        val random = rng ?: Random.Default
        if (random.nextDouble() >= p) return 0.0
        return a * decileQuantile(flow.amount.residualDeciles, random.nextDouble())
    }

    /** Rent of a new home for the person's city and household (rent model), in 2025 CHF. */
    fun marketRent(s: FinancialStatement, quantile: Double): Double {
        val rm = model.rentModel
        return predictRaw(rm.base, rm.factors, rm.unknown, features(s)) * decileQuantile(rm.residualDeciles, quantile)
    }

    // ------------------------------------------------------------------------------------------ step

    /** Apply the pending changes with month <= t. Returns the statement, the cash paid out by new loans and the
     *  quantile of a requested market rent (null if nobody moves this month). */
    private fun applyDueChanges(s: FinancialStatement, t: Int): Triple<FinancialStatement, Double, Double?> {
        var person = s.person
        var income = s.monthlyIncomeChf
        var loan = s.loan
        val expenses = LinkedHashMap(s.eventExpensesChf)
        var loanCash = 0.0
        var rentQuantile: Double? = null
        val still = s.pendingChanges.filter { it.month > t }
        for (ch in s.pendingChanges) {
            if (ch.month > t) continue
            when {
                ch.employment != null -> person = person.copy(employment = ch.employment)
                ch.factKey != null -> person = person.copy(facts = person.facts + (ch.factKey to ch.factValue!!))
                ch.flag != null -> if (ch.flag !in person.flags) person = person.copy(flags = person.flags + ch.flag)
                ch.household != null -> person = person.copy(household = ch.household)
                ch.incomeFactor != null -> income = round2(income * ch.incomeFactor)
                ch.marketRentQuantile != null -> rentQuantile = ch.marketRentQuantile
                ch.monthlyIncomeChf != null -> income = ch.monthlyIncomeChf
                ch.incomeDeltaChf != null -> income = round2(income + ch.incomeDeltaChf)
                ch.expenseCategory != null -> {
                    val v = round2((expenses[ch.expenseCategory] ?: 0.0) + ch.expenseDeltaChf!!)
                    if (abs(v) < 0.005) expenses.remove(ch.expenseCategory) else expenses[ch.expenseCategory] = v
                }
                ch.loan != null -> {
                    loan = mergeLoan(loan, ch.loan)
                    loanCash += ch.loan.principalChf
                }
            }
        }
        val next = s.copy(person = person, monthlyIncomeChf = income, loan = loan, eventExpensesChf = expenses,
                          pendingChanges = still)
        return Triple(next, loanCash, rentQuantile)
    }

    fun step(s: FinancialStatement, mode: SimMode = SimMode.EXPECTED, rng: Random? = null): FinancialStatement {
        val t = s.month
        val period = nextPeriod(s.period)
        val cm = calendarMonth(period)
        val priceIndex = (1 + priceInflation).pow(t / 12.0)       // learned flows are in 2025 CHF

        // 0. changes decided by events
        val (afterChanges, loanCash, rentQuantile) = applyDueChanges(s, t)
        var person = afterChanges.person

        // 1. trends, every month from the second one, 1/12 of the yearly rate (compounded). Event expenses keep the
        //    amount the event set, so their end cancels exactly.
        var income = afterChanges.monthlyIncomeChf
        var rent = afterChanges.monthlyRentChf
        val eventExpenses = afterChanges.eventExpensesChf
        if (t > 0) {
            var career = 1.0
            if (person.employment in WORKING) {
                val ageNow = person.age + (t % 12) / 12.0
                career = careerFactor(person.facts[FactKey.OCCUPATION], ageNow - 1.0 / 12, ageNow)
            }
            income = round2(income * monthly(wageGrowth) * career)
            rent = round2(rent * monthly(rentGrowth))
        }
        if (rentQuantile != null) {           // a new home: market rent for the new city / household, at today's level
            rent = round2(marketRent(afterChanges, rentQuantile) * (1 + rentGrowth).pow(t / 12.0))
        }
        val cur = afterChanges.copy(monthlyIncomeChf = income, monthlyRentChf = rent)

        // 2. flows of the month, from the state at its start
        val feats = features(cur)
        val flows = model.flows.filter { cm in it.months }
        val byKey = flows.associateBy { it.key }
        val inc = linkedMapOf<String, Double>()
        val exp = linkedMapOf<String, Double>()
        inc["salary"] = income * (byKey["salary"]?.let { flowAmount(it, feats, mode, rng) } ?: 1.0)
        exp["rent"] = rent * (byKey["rent"]?.let { flowAmount(it, feats, mode, rng) } ?: 1.0)
        for ((category, v) in eventExpenses) exp[category.name] = v
        for (f in flows) {
            // an event's TUITION replaces the learned tuition of the dataset students (never counted twice)
            if (f.key == "salary" || f.key == "rent") continue
            if (f.expenseCategory == ExpenseCategory.TUITION && ExpenseCategory.TUITION in eventExpenses) continue
            val amt = flowAmount(f, feats, mode, rng) * (if (f.basis == FlowBasis.INCOME) income else priceIndex)
            if (amt != 0.0) (if (f.direction == FlowDirection.IN) inc else exp)[f.key] = amt
        }
        if (loanCash != 0.0) inc["loan"] = loanCash

        // 3. loan, financing, investments
        var loan: Loan? = cur.loan
        var payment = 0.0
        if (loan != null && loan.principalChf > 0) {
            val interest = loan.principalChf * loan.annualRate / 12
            payment = round2(min(loan.monthlyPaymentChf, loan.principalChf + interest))
            loan = loan.copy(principalChf = round2(loan.principalChf + interest - payment))
        }
        val financing = if (s.cashChf < 0) round2(-s.cashChf * rules.financingRateAnnual / 12) else 0.0
        val investments = cur.investmentsChf * (1 + rules.investmentReturnAnnual / 12)

        // 4. accounting
        val incomeLines = LinkedHashMap(inc.mapValues { round2(it.value) })
        val expenseLines = LinkedHashMap(exp.mapValues { round2(it.value) })
        val net = round2(incomeLines.values.sum() - expenseLines.values.sum() - payment - financing)
        if ((t + 1) % 12 == 0) person = person.copy(age = person.age + 1)
        return FinancialStatement(
            profileId = s.profileId, month = t + 1, period = period, person = person,
            cashChf = round2(s.cashChf + net), investmentsChf = round2(investments), loan = loan,
            monthlyIncomeChf = round2(income), monthlyRentChf = round2(rent),
            eventExpensesChf = LinkedHashMap(eventExpenses.filterValues { abs(it) > 0.005 }.mapValues { round2(it.value) }),
            cashFlow = CashFlow(incomeChf = incomeLines, expensesChf = expenseLines, loanPaymentChf = payment,
                                financingCostChf = financing, netChf = net),
            goals = cur.goals, pendingChanges = cur.pendingChanges,
        )
    }

    fun run(s: FinancialStatement, months: Int, mode: SimMode = SimMode.EXPECTED,
            rng: Random? = null): List<FinancialStatement> {
        val out = mutableListOf(s)
        repeat(months) { out += step(out.last(), mode, rng) }
        return out
    }

    // ------------------------------------------------------------------------------------------ trends

    private fun monthly(rate: Double): Double = (1 + rate).pow(1.0 / 12)

    /** Pay change from ageFrom to ageTo on the career curve of the person's occupation (BFS LSE). */
    private fun careerFactor(occupation: String?, ageFrom: Double, ageTo: Double): Double {
        val curves = careerCurves ?: return 1.0
        val name = curves.occupationCurve[occupation ?: ""] ?: curves.defaultCurve
        val curve = curves.curves[name] ?: curves.curves.getValue(curves.defaultCurve)
        return careerLevel(curve, ageTo) / careerLevel(curve, ageFrom)
    }
}

// ---------------------------------------------------------------------------------------------- pure functions

/** base x product of the factors of the person's levels; a level not in a map uses unknown[feature] (or 1). */
fun predictRaw(base: Double, factors: Map<String, Map<String, Double>>, unknown: Map<String, Double>,
               feats: Map<String, String>): Double {
    var product = 1.0
    for ((feature, levels) in factors) {
        product *= levels[feats[feature]] ?: unknown[feature] ?: 1.0
    }
    return base * product
}

/** Value at quantile q (0..1) of a distribution given by its 11 deciles (linear in between). */
fun decileQuantile(deciles: List<Double>, q: Double): Double {
    val u = min(max(q, 0.0), 1.0) * 10
    val i = min(floor(u).toInt(), 9)
    return deciles[i] + (deciles[i + 1] - deciles[i]) * (u - i)
}

/** Median wage at `age`: linear between the survey points, flat before the first and after flatAfterAge. */
fun careerLevel(curve: CareerCurve, age: Double): Double {
    val pts = curve.points
    val a = min(age, curve.flatAfterAge)
    if (a <= pts[0][0]) return pts[0][1]
    for (i in 0 until pts.size - 1) {
        val (a0, v0) = pts[i]
        val (a1, v1) = pts[i + 1]
        if (a <= a1) return v0 + (v1 - v0) * (a - a0) / (a1 - a0)
    }
    return pts.last()[1]
}

/** '<e1', 'e1-e2', ..., 'ek+' with edges printed like Python's "{:g}" for the values we use. No edges -> UNKNOWN. */
fun bandLabel(value: Double, edges: List<Double>?): String {
    if (edges.isNullOrEmpty()) return "UNKNOWN"
    fun fmt(x: Double): String = if (x == floor(x) && abs(x) < 1e6) x.toLong().toString() else x.toString()
    if (value < edges[0]) return "<${fmt(edges[0])}"
    for (i in 0 until edges.size - 1) {
        if (value < edges[i + 1]) return "${fmt(edges[i])}-${fmt(edges[i + 1])}"
    }
    return "${fmt(edges.last())}+"
}

/** The data have at most one loan per person: a new one is merged (principal and payment add up, the rate is the
 *  principal-weighted mean). */
fun mergeLoan(old: Loan?, new: Loan): Loan {
    if (old == null || old.principalChf <= 0) return new
    val p = old.principalChf + new.principalChf
    val rate = (old.principalChf * old.annualRate + new.principalChf * new.annualRate) / p
    return Loan(principalChf = round2(p), annualRate = round6(rate),
                monthlyPaymentChf = round2(old.monthlyPaymentChf + new.monthlyPaymentChf))
}
