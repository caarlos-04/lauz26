package com.lauzhack.twin.ui

import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.Goal
import com.lauzhack.twin.model.netWorthChf

/*
 * Read-only shortcuts over main's compact model (docs/financial-statement.md), so the screens stay readable.
 * Only cheap derived values that the doc defines as "computed, not stored" live here.
 */

val FinancialStatement.cash: Double get() = cashChf
val FinancialStatement.netWorth: Double get() = netWorthChf()
val FinancialStatement.debt: Double get() = loan?.principalChf ?: 0.0
val FinancialStatement.employment: Employment get() = person.employment

/** Net cash flow of the last simulated month; at month 0 (no flows yet) income minus rent and event expenses. */
val FinancialStatement.monthlyNet: Double
    get() = cashFlow?.netChf
        ?: (monthlyIncomeChf - monthlyRentChf - eventExpensesChf.values.sum() - (loan?.monthlyPaymentChf ?: 0.0))

/** Statement at the start of turn [index] (the opening statement for turn 0). */
fun GameSession.stateBefore(index: Int): FinancialStatement = if (index == 0) initial else turns[index - 1].stateAfter

/** Latest statement of the session. */
val GameSession.current: FinancialStatement get() = turns.lastOrNull()?.stateAfter ?: initial

// ---------- Goals (docs/financial-statement.md section 2) ----------

/** The value a goal is measured against, with the engine's rule (cash for savings-type goals, net worth for the rest). */
fun Goal.basisChf(s: FinancialStatement): Double = com.lauzhack.twin.engine.goalValue(this, s)

fun Goal.progress(s: FinancialStatement): Double = if (targetChf <= 0) 1.0 else (basisChf(s) / targetChf).coerceIn(0.0, 1.0)

/** Months from the opening statement ("2025-01-01", month 0) to the end of the goal's target date ("2029-12-31"). */
fun Goal.targetMonth(opening: FinancialStatement): Int {
    val (y0, m0) = opening.period.split("-").let { it[0].toInt() to it[1].toInt() }
    val (y, m) = targetDate.split("-").let { it[0].toInt() to it[1].toInt() }
    return (y - y0) * 12 + (m - m0) + 1
}
