package com.lauzhack.twin.ui

import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.GameSession

/*
 * Read-only shortcuts over main's model, so the screens stay readable. They only read fields the engine
 * already computed (balance sheet, metrics); nothing is recomputed here.
 */

val FinancialStatement.cash: Double get() = balanceSheet.cashChf
val FinancialStatement.netWorth: Double get() = balanceSheet.netWorthChf
val FinancialStatement.monthlyNet: Double get() = metrics.monthlyNetCashFlowChf
val FinancialStatement.employment: Employment get() = person.employment

/** Statement at the start of turn [index] (the opening statement for turn 0). */
fun GameSession.stateBefore(index: Int): FinancialStatement = if (index == 0) initial else turns[index - 1].stateAfter

/** Latest statement of the session. */
val GameSession.current: FinancialStatement get() = turns.lastOrNull()?.stateAfter ?: initial
