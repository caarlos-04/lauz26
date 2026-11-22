package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.GameEngine
import com.lauzhack.twin.fake.FakeData
import com.lauzhack.twin.fake.FakeTwinFactory
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.Loan
import com.lauzhack.twin.model.netWorthChf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The balances and totals the panel can open from the top. */
private val TOP = listOf(Figure.NetWorth, Figure.Cash, Figure.Investments, Figure.Debt, Figure.Income, Figure.Expenses, Figure.Financing)

/**
 * What the Explore panel promises, checked on every node of [tree]: for the months since the last decision, for the
 * whole life and for every single month, each figure is the sum of its rows, and going one level down never loses a
 * franc. Returns the number of breakdowns checked. Shared with the test on the real content.
 */
fun assertEveryBreakdownAddsUp(engine: GameEngine, tree: LifeTree, monthsPerTurn: Int = GameConfig().monthsPerTurn): Int {
    var checked = 0
    tree.nodes.forEach { (key, session) ->
        val months = engine.months(session)
        val last = session.turns.size * monthsPerTurn
        assertEquals(last + 1, months.size, "node '$key': one statement per month")
        assertEquals(session.current, months.last(), "node '$key': the replay ends in the node's snapshot")
        months.forEachIndexed { m, s -> assertEquals(m, s.month) }

        val spans = (listOf(rangeOf(Scope.TURN, last, monthsPerTurn), rangeOf(Scope.LIFE, last, monthsPerTurn)) +
            (1..last).map { Span(it - 1, it) } + Span(0, 0)).distinct()
        for (span in spans) {
            val where = "node '$key' months ${span.from}..${span.to}"
            for (figure in TOP) {
                val b = breakdown(months, figure, span)
                assertTrue(abs(b.residual) < 0.01, "$where $figure: residual ${b.residual}")
                checked++
            }
            // One level down gives the same money: net worth -> its parts, cash -> its flows, flows -> their lines.
            val start = months[span.from]
            val end = months[span.to]
            val cash = breakdown(months, Figure.Cash, span)
            val debt = breakdown(months, Figure.Debt, span)
            val investments = breakdown(months, Figure.Investments, span)
            assertEquals(end.netWorthChf() - start.netWorthChf(), cash.change + investments.change - debt.change, 0.01, "$where: net worth change")
            for (total in listOf(Figure.Income, Figure.Expenses)) {
                val b = breakdown(months, total, span)
                b.rows.forEach { row ->
                    val line = row.drill as Figure.Line
                    assertEquals(row.chf, breakdown(months, line, span).value, 0.01, "$where ${line.key}")
                    assertEquals(row.chf, series(months, line, span.from + 1, span.to).sum(), 0.01, "$where ${line.key}: the chart")
                }
                assertEquals(b.value, cash.rows.firstOrNull { it.drill == total }?.chf ?: 0.0, 0.01, "$where $total in cash")
            }
            // The loan payment leaves cash and lowers the debt by the same amount.
            assertEquals(cash.rows.firstOrNull { it.id == "loan_payments" }?.chf ?: 0.0, debt.rows.firstOrNull { it.id == "debt_paid" }?.chf ?: 0.0, 0.01, where)
            // Interest is never negative, and only a loan charges it.
            val interest = debt.rows.firstOrNull { it.id == "debt_interest" }?.chf ?: 0.0
            assertTrue(interest > -0.01, "$where: interest $interest")
        }
        // A period is the sum of its months, for every figure that is a flow.
        val life = Span(0, last)
        for (flow in listOf(Figure.Income, Figure.Expenses, Figure.Financing)) {
            assertEquals(breakdown(months, flow, life).value, (1..last).sumOf { breakdown(months, flow, Span(it - 1, it)).value }, 0.01, "node '$key' $flow by month")
        }
        // The same money as the engine's attribution (the waterfall of the results).
        val attribution = engine.attribution(session).items.associate { it.key to it.chf }
        (breakdown(months, Figure.Income, life).rows + breakdown(months, Figure.Expenses, life).rows).forEach { row ->
            if (row.id != "income:loan") assertEquals(attribution[row.id] ?: 0.0, row.chf, 0.01, "node '$key' ${row.id} vs attribution")
        }
        assertEquals(-(attribution["loan_interest"] ?: 0.0), breakdown(months, Figure.Debt, life).rows.firstOrNull { it.id == "debt_interest" }?.chf ?: 0.0, 0.01, "node '$key' loan interest")
        assertEquals(attribution["financing_cost"] ?: 0.0, breakdown(months, Figure.Financing, life).value, 0.01, "node '$key' financing")
    }
    return checked
}

class BreakdownTest {
    private val engine = FakeData.engine()
    private val all = run {
        val game = FakeTwinFactory().demo()
        engine.explore(LifeTree.start(GameSession(game.opening, 11L, game.preferences, emptyList(), GameConfig())))
    }

    @Test
    fun everyFigureOfEveryMomentAddsUp() {
        assertTrue(assertEveryBreakdownAddsUp(engine, all) > 1000)
    }

    @Test
    fun theOpeningStatementHasNoFlows() {
        val months = engine.months(all.root)
        assertEquals(1, months.size)
        val cash = breakdown(months, Figure.Cash, Span(0, 0))
        assertEquals(listOf("start"), cash.rows.map { it.id })
        assertEquals(months[0].cashChf, cash.value)
        assertTrue(breakdown(months, Figure.Income, Span(0, 0)).rows.isEmpty())
    }

    @Test
    fun theWayDownToAFigure() {
        assertEquals(listOf(Figure.NetWorth), pathTo(Figure.NetWorth))
        assertEquals(listOf(Figure.NetWorth, Figure.Debt), pathTo(Figure.Debt))
        assertEquals(listOf(Figure.NetWorth, Figure.Cash, Figure.Expenses, Figure.Line("expense:rent")), pathTo(Figure.Line("expense:rent")))
        assertEquals(listOf(Figure.NetWorth, Figure.Cash, Figure.Income, Figure.Line("income:salary")), pathTo(Figure.Line("income:salary")))
        // Every row that can be opened leads to a figure whose path goes through where it was opened from.
        val life = all.lives.first()
        val months = engine.months(all.nodes.getValue(life))
        val span = Span(0, months.size - 1)
        for (figure in TOP) breakdown(months, figure, span).rows.mapNotNull { it.drill }.forEach { assertTrue(figure in pathTo(it), "$figure -> $it") }
        assertEquals(Figure.Debt, figureOf("loan_interest"))
        assertEquals(Figure.Line("expense:groceries"), figureOf("expense:groceries"))
    }

    @Test
    fun theMonthsThePanelLooksAt() {
        assertEquals(Span(24, 48), rangeOf(Scope.TURN, 48, 24))
        assertEquals(Span(0, 48), rangeOf(Scope.LIFE, 48, 24))
        assertEquals(Span(0, 0), rangeOf(Scope.TURN, 0, 24))
        assertEquals(Span(29, 30), spanOf(Inspect("", Figure.Cash, Scope.TURN, month = 30), 48, 24))
        assertEquals(Span(23, 24), spanOf(Inspect("", Figure.Cash, Scope.TURN, month = 3), 48, 24), "a month before the range is its first one")
        assertEquals(Span(0, 0), spanOf(Inspect("", Figure.Cash, Scope.LIFE, month = 0), 48, 24))
        assertEquals(Span(24, 48), spanOf(Inspect("", Figure.Cash, Scope.TURN), 48, 24))
    }

    @Test
    fun originsAndTrendsOfTheMainCategories() {
        assertEquals(Origin.EVENT, originOf("income:salary"))
        assertEquals(Origin.EVENT, originOf("expense:TUITION"))
        assertEquals(Origin.DATASET, originOf("expense:groceries"))
        assertEquals(Origin.DATASET, originOf("income:bonus"))
        assertEquals(Origin.ASSUMPTION, originOf("financing_cost"))
        assertEquals(Origin.ASSUMPTION, originOf("investment_growth"))
        assertEquals(Trend.WAGES, trendOf("income:salary"))
        assertEquals(Trend.RENT, trendOf("expense:rent"))
        assertEquals(Trend.PRICES, trendOf("expense:groceries"))
        assertEquals(Trend.FIXED, trendOf("expense:TUITION"))
        assertEquals(Trend.NONE, trendOf("income:loan"))
    }

    @Test
    fun aLoanIsPaidOffInItsTerm() {
        assertEquals(0, monthsLeft(null))
        // 10,000 at 6 % over 60 months: the annuity is 193.33.
        assertEquals(60, monthsLeft(Loan(principalChf = 10_000.0, annualRate = 0.06, monthlyPaymentChf = 193.33)))
        assertNull(monthsLeft(Loan(principalChf = 10_000.0, annualRate = 0.06, monthlyPaymentChf = 40.0)), "the payment does not cover the interest")
    }
}
