package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.GameEngine
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.Turn
import kotlin.math.abs

/** Where a figure comes from. */
enum class Origin {
    DATASET,     // learned from the challenge's synthetic dataset (flow model, profile)
    PUBLIC,      // public Swiss data (BFS, SNB...): trends
    EVENT,       // set by a decision of the game (its case)
    ASSUMPTION,  // a rule or value we assumed (content/assumptions.json)
}

/** One line of the explanation: a cash-flow category over the period, signed (income +, expense -). */
data class TraceLine(val key: String, val chf: Double, val origin: Origin)

/**
 * How the net worth went from [startNetWorth] to [endNetWorth] between two moments of a life (months [fromMonth] to
 * [toMonth]): every category of money in and out, with its origin. [residual] = start + sum(lines) - end; the
 * engine guarantees it is ~0 (tested), and the UI shows it as the check that the numbers add up.
 */
data class Trace(
    val fromMonth: Int,
    val toMonth: Int,
    val startNetWorth: Double,
    val endNetWorth: Double,
    val startCash: Double,
    val endCash: Double,
    val lines: List<TraceLine>,
    val residual: Double,
    /** The turns in between (one for a node and its parent; every turn for a whole life). */
    val turns: List<Turn>,
)

/** Origin of an attribution key ("income:salary", "expense:TUITION", "financing_cost"...). */
fun originOf(key: String): Origin {
    val name = key.substringAfter(':')
    return when {
        key == "income:salary" -> Origin.EVENT                          // its level; the growth is public data
        key.startsWith("expense:") && ExpenseCategory.entries.any { it.name == name } -> Origin.EVENT
        key == "loan_interest" -> Origin.EVENT
        key == "financing_cost" || key == "investment_growth" -> Origin.ASSUMPTION
        else -> Origin.DATASET                                          // learned flows: bonus, rent, groceries...
    }
}

/**
 * The explanation of what happened between [from] and [to], where [from]'s turns are a prefix of [to]'s (a node and
 * one of its ancestors, e.g. its parent or the start). Pure given the engine.
 */
fun GameEngine.trace(from: GameSession, to: GameSession): Trace {
    require(to.turns.take(from.turns.size) == from.turns) { "from must be an ancestor of to" }
    val a = if (from.turns.isEmpty()) emptyMap() else attribution(from).items.associate { it.key to it.chf }
    val b = attribution(to).items.associate { it.key to it.chf }
    val lines = (a.keys + b.keys).map { k -> TraceLine(k, (b[k] ?: 0.0) - (a[k] ?: 0.0), originOf(k)) }
        .filter { abs(it.chf) >= 0.5 }
        .sortedByDescending { abs(it.chf) }
    val start = from.current
    val end = to.current
    return Trace(
        fromMonth = start.month,
        toMonth = end.month,
        startNetWorth = start.netWorth,
        endNetWorth = end.netWorth,
        startCash = start.cash,
        endCash = end.cash,
        lines = lines,
        residual = start.netWorth + lines.sumOf { it.chf } - end.netWorth,
        turns = to.turns.drop(from.turns.size),
    )
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
