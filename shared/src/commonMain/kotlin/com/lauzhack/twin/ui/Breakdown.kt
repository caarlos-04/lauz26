package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.round2
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Loan
import com.lauzhack.twin.model.netWorthChf
import kotlin.math.abs
import kotlin.math.min

/*
 * "Where does this number come from?" (the Explore panel). Any figure of any moment of a life is broken down into the
 * pieces that make it, and each piece can be opened again, down to its origin:
 *
 *   net worth = cash + investments - debt                                   (balance)
 *   cash      = cash at the start + income - expenses - loan payments - interest on negative cash   (flows)
 *   income / expenses = one line per category                               (flows)
 *   a line    = where it comes from: dataset, public data, a decision, an assumption            (origin)
 *
 * Nothing is computed here that the engine did not compute: the functions only read and add up the monthly statements
 * (GameEngine.months). Every breakdown carries its [Breakdown.residual], which must be ~0 (tested on every node).
 */

/** Where a figure comes from. */
enum class Origin {
    DATASET,     // learned from the challenge's synthetic dataset (flow model, profile)
    PUBLIC,      // public Swiss data (BFS, SNB...): trends
    EVENT,       // set by a decision of the game (its drawn values)
    ASSUMPTION,  // a rule or value we assumed (content/assumptions.json)
}

/** Origin of a flow key ("income:salary", "expense:TUITION", "financing_cost"...). */
fun originOf(key: String): Origin {
    val name = key.substringAfter(':')
    return when {
        key == "income:salary" -> Origin.EVENT                          // its level; the growth is public data
        key == "income:loan" -> Origin.EVENT
        key.startsWith("expense:") && ExpenseCategory.entries.any { it.name == name } -> Origin.EVENT
        key == "loan_interest" -> Origin.EVENT
        key == "financing_cost" || key == "investment_growth" -> Origin.ASSUMPTION
        else -> Origin.DATASET                                          // learned flows: bonus, rent, groceries...
    }
}

/** A figure that can be explored. Stocks are balances at a moment; flows are money moved during a period. */
sealed interface Figure {
    data object NetWorth : Figure
    data object Cash : Figure
    data object Investments : Figure
    data object Debt : Figure
    data object Income : Figure
    data object Expenses : Figure
    data object Financing : Figure

    /** One category of income or expense: "income:salary", "expense:rent", "expense:TUITION"... */
    data class Line(val key: String) : Figure

    val isStock: Boolean get() = this is NetWorth || this is Cash || this is Investments || this is Debt
}

/** The way down to [figure] from the net worth: the breadcrumbs of the panel. */
fun pathTo(figure: Figure): List<Figure> = when (figure) {
    Figure.NetWorth -> listOf(Figure.NetWorth)
    Figure.Cash, Figure.Investments, Figure.Debt -> listOf(Figure.NetWorth, figure)
    Figure.Income, Figure.Expenses, Figure.Financing -> listOf(Figure.NetWorth, Figure.Cash, figure)
    is Figure.Line -> listOf(Figure.NetWorth, Figure.Cash, if (figure.key.startsWith("income:")) Figure.Income else Figure.Expenses, figure)
}

/** The figure behind a key of the engine's attribution (the waterfall): its lines, or the stock that explains it. */
fun figureOf(attributionKey: String): Figure = when (attributionKey) {
    "loan_interest" -> Figure.Debt
    "financing_cost" -> Figure.Financing
    "investment_growth" -> Figure.Investments
    else -> Figure.Line(attributionKey)
}

/** Which months the panel looks at: the months since the last decision, or the whole life up to the node. */
enum class Scope { TURN, LIFE }

/**
 * A period of a life: the flows of months [from]+1..[to] (statement indices), which take the balances of month [from]
 * to those of month [to]. A single month m is Span(m - 1, m); the opening statement alone is Span(0, 0).
 */
data class Span(val from: Int, val to: Int) {
    init { require(from in 0..to) { "Bad span $from..$to" } }
    val months: Int get() = to - from
}

enum class RowKind {
    PART,     // a part of a balance (net worth = cash + investments - debt)
    START,    // the balance at the start of the period
    FLOW,     // money in (+) or out (-) during the period
}

/** One line of a breakdown, signed as it adds to the headline. [id] is a figure name or a flow key (the UI labels it). */
data class BreakdownRow(val id: String, val chf: Double, val kind: RowKind, val drill: Figure? = null, val origin: Origin? = null)

/**
 * [figure] over [span]: its [value] (the balance at the end of the span for a stock, the total moved for a flow), the
 * [rows] that add up to it and the [residual] (sum of rows - value: ~0).
 */
data class Breakdown(val figure: Figure, val span: Span, val value: Double, val rows: List<BreakdownRow>) {
    val residual: Double get() = rows.sumOf { it.chf } - value

    /** For a stock: how much it changed during the span. */
    val change: Double get() = rows.filter { it.kind == RowKind.FLOW }.sumOf { it.chf }
}

// ---------------------------------------------------------------------------------------------- reading the months

private fun debtOf(s: FinancialStatement): Double = s.loan?.principalChf ?: 0.0

/** The balance of a stock at one month. */
fun stockAt(s: FinancialStatement, figure: Figure): Double = when (figure) {
    Figure.NetWorth -> s.netWorthChf()
    Figure.Cash -> s.cashChf
    Figure.Investments -> s.investmentsChf
    Figure.Debt -> debtOf(s)
    else -> error("$figure is not a balance")
}

/** What a flow moved in one month, signed as it changes cash (income +, expenses -). 0 at month 0 (no flows yet). */
fun flowAt(s: FinancialStatement, figure: Figure): Double {
    val cf = s.cashFlow ?: return 0.0
    return when (figure) {
        Figure.Income -> cf.incomeChf.values.sum()
        Figure.Expenses -> -cf.expensesChf.values.sum()
        Figure.Financing -> -cf.financingCostChf
        is Figure.Line -> {
            val name = figure.key.substringAfter(':')
            if (figure.key.startsWith("income:")) cf.incomeChf[name] ?: 0.0 else -(cf.expensesChf[name] ?: 0.0)
        }
        else -> error("$figure is not a flow")
    }
}

/** One value of [figure] per month [from]..[to] (the small chart of the panel): balances for a stock, the month's flow otherwise. */
fun series(months: List<FinancialStatement>, figure: Figure, from: Int, to: Int): List<Double> =
    (from..to).map { m -> if (figure.isStock) stockAt(months[m], figure) else flowAt(months[m], figure) }

/** Interest the loan charged in month [m]: what the debt grew before the payment (a new loan paid out is not interest). */
private fun loanInterest(months: List<FinancialStatement>, m: Int): Double {
    val cur = months[m]
    val cf = cur.cashFlow ?: return 0.0
    return debtOf(cur) - debtOf(months[m - 1]) - (cf.incomeChf["loan"] ?: 0.0) + cf.loanPaymentChf
}

private fun sumOver(months: List<FinancialStatement>, span: Span, f: (Int) -> Double): Double =
    ((span.from + 1)..span.to).sumOf(f)

/** Totals of every income (or expense) category over the span, largest first, signed as they change cash. */
private fun lines(months: List<FinancialStatement>, span: Span, income: Boolean): List<BreakdownRow> {
    val totals = linkedMapOf<String, Double>()
    for (m in (span.from + 1)..span.to) {
        val cf = months[m].cashFlow ?: continue
        for ((k, v) in if (income) cf.incomeChf else cf.expensesChf) totals[k] = (totals[k] ?: 0.0) + v
    }
    val prefix = if (income) "income:" else "expense:"
    return totals.filterValues { abs(it) >= 0.005 }.map { (k, v) ->
        val key = prefix + k
        BreakdownRow(key, if (income) v else -v, RowKind.FLOW, Figure.Line(key), originOf(key))
    }.sortedByDescending { abs(it.chf) }
}

// ---------------------------------------------------------------------------------------------- the breakdowns

/** [figure] over [span] of the life [months] (index = month), as rows that add up to it. Pure. */
fun breakdown(months: List<FinancialStatement>, figure: Figure, span: Span): Breakdown {
    require(span.to < months.size) { "Month ${span.to} is not in this life (${months.size - 1} months)" }
    val start = months[span.from]
    val end = months[span.to]
    fun flow(id: String, chf: Double, drill: Figure? = null, origin: Origin? = null) =
        BreakdownRow(id, chf, RowKind.FLOW, drill, origin).takeIf { abs(chf) >= 0.005 }
    val rows: List<BreakdownRow> = when (figure) {
        Figure.NetWorth -> listOf(
            BreakdownRow("cash", end.cashChf, RowKind.PART, Figure.Cash),
            BreakdownRow("investments", end.investmentsChf, RowKind.PART, Figure.Investments),
            BreakdownRow("debt", -debtOf(end), RowKind.PART, Figure.Debt),
        )
        Figure.Cash -> listOfNotNull(
            BreakdownRow("start", start.cashChf, RowKind.START),
            flow("income", sumOver(months, span) { flowAt(months[it], Figure.Income) }, Figure.Income),
            flow("expenses", sumOver(months, span) { flowAt(months[it], Figure.Expenses) }, Figure.Expenses),
            flow("loan_payments", -sumOver(months, span) { months[it].cashFlow?.loanPaymentChf ?: 0.0 }, Figure.Debt),
            flow("financing", sumOver(months, span) { flowAt(months[it], Figure.Financing) }, Figure.Financing, Origin.ASSUMPTION),
        )
        Figure.Investments -> listOfNotNull(
            BreakdownRow("start", start.investmentsChf, RowKind.START),
            flow("investment_growth", end.investmentsChf - start.investmentsChf, origin = Origin.ASSUMPTION),
        )
        Figure.Debt -> listOfNotNull(
            BreakdownRow("start", debtOf(start), RowKind.START),
            flow("debt_new", sumOver(months, span) { months[it].cashFlow?.incomeChf?.get("loan") ?: 0.0 }, origin = Origin.EVENT),
            flow("debt_interest", sumOver(months, span) { loanInterest(months, it) }, origin = Origin.EVENT),
            flow("debt_paid", -sumOver(months, span) { months[it].cashFlow?.loanPaymentChf ?: 0.0 }),
        )
        Figure.Income -> lines(months, span, income = true)
        Figure.Expenses -> lines(months, span, income = false)
        Figure.Financing -> listOfNotNull(flow("financing", sumOver(months, span) { flowAt(months[it], Figure.Financing) }, origin = Origin.ASSUMPTION))
        is Figure.Line -> listOfNotNull(flow(figure.key, sumOver(months, span) { flowAt(months[it], figure) }, origin = originOf(figure.key)))
    }
    val value = if (figure.isStock) stockAt(end, figure) else sumOver(months, span) { flowAt(months[it], figure) }
    return Breakdown(figure, span, value, rows)
}

// ---------------------------------------------------------------------------------------------- the origin of a line

/** What makes a line grow from one month to the next, besides the decisions. */
enum class Trend {
    WAGES,    // the Swiss wage index and, while working, the career curve
    RENT,     // the Swiss rent index
    PRICES,   // Swiss inflation (learned flows)
    FIXED,    // the amount a decision set: it does not grow
    NONE,     // a one-off (a loan paid out)
}

fun trendOf(key: String): Trend = when {
    key == "income:salary" || key == "income:bonus" -> Trend.WAGES      // the bonus is a share of the salary
    key == "income:loan" -> Trend.NONE
    key == "expense:rent" -> Trend.RENT
    originOf(key) == Origin.EVENT -> Trend.FIXED
    else -> Trend.PRICES
}

/** The yearly rates of macro.json the panel quotes (fractions: 0.01 = 1 %). */
data class TrendRates(val wages: Double, val rent: Double, val prices: Double)

/** How a line behaved over a span: in how many months it appears and its first and last monthly amount (signed). */
data class LineStats(val monthsPresent: Int, val months: Int, val first: Double?, val last: Double?)

fun lineStats(months: List<FinancialStatement>, line: Figure.Line, span: Span): LineStats {
    val values = ((span.from + 1)..span.to).map { flowAt(months[it], line) }
    val present = values.filter { abs(it) >= 0.005 }
    return LineStats(present.size, values.size, present.firstOrNull(), present.lastOrNull())
}

/** The flow key an effect of a decision sets directly, or null for a life change (which moves the learned flows). */
fun effectKey(effect: Effect): String? = when (effect) {
    is Effect.SetIncome, is Effect.IncomeDelta, is Effect.IncomeFactor -> "income:salary"
    is Effect.ExpenseDelta -> "expense:${effect.category.name}"
    is Effect.OneOff -> "expense:${ExpenseCategory.OTHER.name}"
    is Effect.MarketRent -> "expense:rent"
    is Effect.AddLiability -> "income:loan"
    is Effect.SetHousehold, is Effect.SetEmployment, is Effect.SetFact, is Effect.AddFlag -> null
}

/** Months [from, to) of an effect, relative to the start of its turn; null `months` = until the end. */
val Effect.startOffset: Int
    get() = when (this) {
        is Effect.SetIncome -> startOffsetMonths
        is Effect.IncomeDelta -> startOffsetMonths
        is Effect.IncomeFactor -> startOffsetMonths
        is Effect.SetHousehold -> startOffsetMonths
        is Effect.MarketRent -> startOffsetMonths
        is Effect.ExpenseDelta -> startOffsetMonths
        is Effect.OneOff -> startOffsetMonths
        is Effect.AddLiability -> 0
        is Effect.SetEmployment -> startOffsetMonths
        is Effect.SetFact -> startOffsetMonths
        is Effect.AddFlag -> startOffsetMonths
    }

// ---------------------------------------------------------------------------------------------- the loan

/** Months until [loan] is paid off with its monthly payment (the simulator's rule); null if the payment never covers it. */
fun monthsLeft(loan: Loan?): Int? {
    var principal = loan?.principalChf ?: return 0
    if (principal <= 0) return 0
    var n = 0
    while (principal > 0.005) {
        val interest = principal * loan.annualRate / 12
        val payment = round2(min(loan.monthlyPaymentChf, principal + interest))
        if (payment <= interest || n >= 1200) return null
        principal = round2(principal + interest - payment)
        n++
    }
    return n
}

// ---------------------------------------------------------------------------------------------- what the panel shows

/**
 * What the Explore panel is showing: [figure] at node [key] of the tree, over the months of [scope], or only [month]
 * when the user picked one in the chart.
 */
data class Inspect(val key: NodeKey, val figure: Figure, val scope: Scope, val month: Int? = null)

/** A request to open the panel on a figure; null fields take the current node and its natural scope. */
data class InspectRequest(val figure: Figure, val key: NodeKey? = null, val scope: Scope? = null, val month: Int? = null)

/** The months the chart of the panel shows: since the last decision, or the whole life (0..[lastMonth]). */
fun rangeOf(scope: Scope, lastMonth: Int, monthsPerTurn: Int): Span =
    if (scope == Scope.LIFE || lastMonth == 0) Span(0, lastMonth) else Span(lastMonth - monthsPerTurn, lastMonth)

/** The period the rows add up: the picked month, or the whole range. */
fun spanOf(inspect: Inspect, lastMonth: Int, monthsPerTurn: Int): Span {
    val range = rangeOf(inspect.scope, lastMonth, monthsPerTurn)
    val m = inspect.month?.coerceIn(range.from, range.to) ?: return range
    return if (m == 0) Span(0, 0) else Span(m - 1, m)
}
