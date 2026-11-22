package com.lauzhack.twin.fake

import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.CashFlow
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.model.Loan
import com.lauzhack.twin.model.PendingChange
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Simplified money engine for the fake (docs/simulator.md, loosely): turns effects into [PendingChange]s and advances
 * [FinancialStatement]s month by month. Placeholder for Dev A's engine; values are illustrative (CHF), not calibrated.
 *
 * Months: statement t = end of month t. A turn starting at statement m simulates months m+1..m+24; an effect with
 * offset o applies at the start of month m+1+o (before that month's flows).
 */
internal class FakeLedger(private val assumptions: Assumptions) {

    // ---------- Effects (start of a turn) ----------

    /** Queues the effects of a case as pending changes (they apply when their month starts). */
    fun applyEffects(s: FinancialStatement, effects: List<Effect>, eventId: String): FinancialStatement =
        s.copy(pendingChanges = s.pendingChanges + effects.flatMap { changesOf(it, s.month + 1, eventId) })

    /** Pending changes of one effect; [first] is the first month of the turn. */
    fun changesOf(effect: Effect, first: Int, eventId: String): List<PendingChange> {
        fun at(offset: Int) = first + offset
        fun ch(month: Int) = PendingChange(month = month, sourceEventId = eventId)
        return when (effect) {
            is Effect.SetIncome -> listOf(ch(at(effect.startOffsetMonths)).copy(monthlyIncomeChf = effect.monthlyChf))
            is Effect.IncomeDelta -> listOfNotNull(
                ch(at(effect.startOffsetMonths)).copy(incomeDeltaChf = effect.deltaChf),
                effect.months?.let { ch(at(effect.startOffsetMonths + it)).copy(incomeDeltaChf = -effect.deltaChf) },
            )
            is Effect.IncomeFactor -> listOfNotNull(
                ch(at(effect.startOffsetMonths)).copy(incomeFactor = effect.factor),
                effect.months?.let { ch(at(effect.startOffsetMonths + it)).copy(incomeFactor = 1 / effect.factor) },
            )
            is Effect.SetHousehold -> listOf(ch(at(effect.startOffsetMonths)).copy(household = effect.household))
            is Effect.MarketRent -> listOf(ch(at(effect.startOffsetMonths)).copy(marketRentQuantile = effect.quantile))
            is Effect.ExpenseDelta -> listOfNotNull(
                ch(at(effect.startOffsetMonths)).copy(expenseCategory = effect.category, expenseDeltaChf = effect.deltaChf),
                effect.months?.let {
                    ch(at(effect.startOffsetMonths + it)).copy(expenseCategory = effect.category, expenseDeltaChf = -effect.deltaChf)
                },
            )
            // A one-off is an OTHER expense during a single month, so it shows up in that month's cash flow.
            is Effect.OneOff -> listOf(
                ch(at(effect.startOffsetMonths)).copy(expenseCategory = ExpenseCategory.OTHER, expenseDeltaChf = effect.chf),
                ch(at(effect.startOffsetMonths + 1)).copy(expenseCategory = ExpenseCategory.OTHER, expenseDeltaChf = -effect.chf),
            )
            is Effect.AddLiability -> listOf(
                ch(first).copy(loan = Loan(effect.principalChf, effect.annualRate, annuity(effect.principalChf, effect.annualRate, effect.termMonths))),
            )
            is Effect.SetEmployment -> listOf(ch(at(effect.startOffsetMonths)).copy(employment = effect.employment))
            is Effect.SetFact -> listOf(ch(at(effect.startOffsetMonths)).copy(factKey = effect.key, factValue = effect.value))
            is Effect.AddFlag -> listOf(ch(at(effect.startOffsetMonths)).copy(flag = effect.flag))
        }
    }

    private fun applyChange(s: FinancialStatement, c: PendingChange): FinancialStatement {
        val p = s.person
        return when {
            c.employment != null -> s.copy(person = p.copy(employment = c.employment))
            c.factKey != null && c.factValue != null -> s.copy(person = p.copy(facts = p.facts + (c.factKey to c.factValue)))
            c.flag != null -> s.copy(person = p.copy(flags = if (c.flag in p.flags) p.flags else p.flags + c.flag))
            c.household != null -> s.copy(person = p.copy(household = c.household))
            c.monthlyIncomeChf != null -> s.copy(monthlyIncomeChf = c.monthlyIncomeChf)
            c.incomeDeltaChf != null -> s.copy(monthlyIncomeChf = max(0.0, s.monthlyIncomeChf + c.incomeDeltaChf))
            c.incomeFactor != null -> s.copy(monthlyIncomeChf = s.monthlyIncomeChf * c.incomeFactor)
            c.marketRentQuantile != null -> s.copy(monthlyRentChf = marketRent(s, c.marketRentQuantile))
            c.expenseCategory != null && c.expenseDeltaChf != null -> {
                val v = (s.eventExpensesChf[c.expenseCategory] ?: 0.0) + c.expenseDeltaChf
                s.copy(eventExpensesChf = if (kotlin.math.abs(v) < 0.005) s.eventExpensesChf - c.expenseCategory else s.eventExpensesChf + (c.expenseCategory to v))
            }
            c.loan != null -> s.copy(cashChf = s.cashChf + c.loan.principalChf, loan = merge(s.loan, c.loan))
            else -> s
        }
    }

    // ---------- Monthly step ----------

    /** Advances one month: due changes, flows, loan, financing cost on negative cash, investment growth. */
    fun step(s0: FinancialStatement, noiseChf: Double = 0.0, returnShock: Double = 0.0): FinancialStatement {
        val next = s0.month + 1
        val s = s0.pendingChanges.filter { it.month <= next }.fold(s0) { acc, c -> applyChange(acc, c) }

        val income = mapOf("salary" to s.monthlyIncomeChf).filterValues { it > 0 }
        val expenses = buildMap {
            put("rent", s.monthlyRentChf)
            put("living", livingCost(s))
            s.eventExpensesChf.forEach { (k, v) ->
                val key = if (k == ExpenseCategory.RENT) "rent" else k.name   // a rent change adds to the rent line
                put(key, (get(key) ?: 0.0) + v)
            }
        }.filterValues { it != 0.0 }
        val loan = s.loan?.takeIf { it.principalChf > 0.005 }
        val interest = loan?.let { it.principalChf * it.annualRate / 12 } ?: 0.0
        val payment = loan?.let { min(it.monthlyPaymentChf, it.principalChf + interest) } ?: 0.0
        val financing = if (s.cashChf < 0) -s.cashChf * assumptions.financingRateAnnual / 12 else 0.0
        val net = income.values.sum() - expenses.values.sum() - payment - financing + noiseChf

        return s.copy(
            month = next,
            period = periodOf(s0, next),
            person = if (next % 12 == 0) s.person.copy(age = s.person.age + 1) else s.person,
            cashChf = s.cashChf + net,
            investmentsChf = s.investmentsChf * (1 + (assumptions.investmentReturnAnnual + returnShock) / 12),
            loan = loan?.let { it.copy(principalChf = max(0.0, it.principalChf + interest - payment)) },
            cashFlow = CashFlow(
                incomeChf = income,
                expensesChf = expenses + (if (noiseChf != 0.0) mapOf("noise" to -noiseChf) else emptyMap()),
                loanPaymentChf = payment,
                financingCostChf = financing,
                netChf = net,
            ),
            pendingChanges = s.pendingChanges.filter { it.month > next },
        )
    }

    /** [start] plus the next [months] statements. */
    fun advance(start: FinancialStatement, months: Int, noise: ((FinancialStatement) -> Double)? = null, returnShock: Double = 0.0): List<FinancialStatement> {
        val out = ArrayList<FinancialStatement>(months + 1)
        out += start
        var s = start
        repeat(months) {
            s = step(s, noise?.invoke(s) ?: 0.0, returnShock)
            out += s
        }
        return out
    }

    // ---------- Fake calibration (illustrative CHF, NOT the learned FlowModel) ----------

    /** Groceries, insurance, transport... by household, plus a share of income above 4,000. */
    private fun livingCost(s: FinancialStatement): Double {
        val base = when (s.person.household) {
            Household.SINGLE -> 1_100.0
            Household.SHARED_HOUSING -> 850.0
            Household.COUPLE -> 1_600.0
            Household.FAMILY -> 2_600.0
        }
        return base + 0.15 * max(0.0, s.monthlyIncomeChf - 4_000.0)
    }

    /** Stand-in for FlowModel.rentModel: median rent by household, cheaper outside the big cities. */
    private fun marketRent(s: FinancialStatement, quantile: Double): Double {
        val median = when (s.person.household) {
            Household.SINGLE -> 1_450.0
            Household.SHARED_HOUSING -> 850.0
            Household.COUPLE -> 1_900.0
            Household.FAMILY -> 2_500.0
        }
        val city = when (s.person.facts[FactKey.CITY]) {
            "Zurich", "Geneva" -> 1.2
            "Lausanne", "Basel", "Lucerne" -> 1.0
            else -> 0.85
        }
        return median * city * (0.7 + 0.6 * quantile.coerceIn(0.0, 1.0))
    }

    private fun annuity(principal: Double, annualRate: Double, months: Int): Double {
        val r = annualRate / 12
        return if (r == 0.0) principal / months else principal * r / (1 - (1 + r).pow(-months))
    }

    private fun merge(old: Loan?, new: Loan): Loan {
        if (old == null || old.principalChf <= 0.005) return new
        val total = old.principalChf + new.principalChf
        return Loan(total, (old.annualRate * old.principalChf + new.annualRate * new.principalChf) / total,
            old.monthlyPaymentChf + new.monthlyPaymentChf)
    }

    /** "2025-01-01" (opening) -> "2025-01" for month 1, then one calendar month per step. */
    private fun periodOf(prev: FinancialStatement, month: Int): String {
        val (y, m) = prev.period.split("-").let { it[0].toInt() to it[1].toInt() }
        val index = y * 12 + (m - 1) + if (prev.month == 0 || month == 0) 0 else 1
        return "${index / 12}-${(index % 12 + 1).toString().padStart(2, '0')}"
    }
}
