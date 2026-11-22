package com.lauzhack.twin.engine

import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Goal
import com.lauzhack.twin.model.netWorthChf
import kotlin.math.pow
import kotlin.math.round

// Small pure helpers shared by the engine. Python reference: tools/statement.py, tools/game.py.

/** Seeded numbers in [0, 1) that are identical in Python (tools/game.py `unit`) and Kotlin: a game is reproducible
 *  on every platform, and the parallel lives get the same luck per turn. */
object PortableRandom {
    const val LUCK_SALT = 1
    const val EVENT_SALT = 2

    fun splitmix64(input: ULong): ULong {
        val x = input + 0x9E3779B97F4A7C15uL
        var z = (x xor (x shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    fun unit(seed: Long, turn: Int, salt: Int): Double {
        val x = (seed * 1_000_003L + turn.toLong() * 31L + salt.toLong()).toULong()
        return (splitmix64(x) shr 11).toDouble() / 9_007_199_254_740_992.0      // 2^53
    }

    fun turnLuck(seed: Long, turn: Int): Double = unit(seed, turn, LUCK_SALT)
}

/** Round to cents, ties to even (as Python's round(x, 2) on the values we produce). */
fun round2(x: Double): Double = round(x * 100.0) / 100.0 + 0.0      // + 0.0 turns -0.0 into 0.0

fun round4(x: Double): Double = round(x * 10_000.0) / 10_000.0

fun round6(x: Double): Double = round(x * 1_000_000.0) / 1_000_000.0

/** Monthly payment that repays `principal` in `months` at `annualRate`. */
fun annuityPayment(principal: Double, annualRate: Double, months: Int): Double {
    val r = annualRate / 12
    return if (r == 0.0) principal / months else principal * r / (1 - (1 + r).pow(-months))
}

/** "2025-01-01" (opening) -> "2025-01"; "2025-01" -> "2025-02"; "2025-12" -> "2026-01". */
fun nextPeriod(period: String): String {
    if (period.length == 10) return period.substring(0, 7)
    val y = period.substring(0, 4).toInt()
    val m = period.substring(5, 7).toInt() + 1
    val year = y + (m - 1) / 12
    val month = (m - 1) % 12 + 1
    return "$year-${month.toString().padStart(2, '0')}"
}

fun calendarMonth(period: String): Int = period.substring(5, 7).toInt()

// ---------------------------------------------------------------------------------------------- goals

/** Cash-like goals are measured with cash, the rest with net worth (assumption, docs/financial-statement.md). */
private val GOAL_BASIS_CASH = setOf("Emergency savings", "Housing deposit")

enum class GoalState { IN_PROGRESS, REACHED, MISSED }

/** True if [goal] is measured on cash (else on net worth). */
fun goalOnCash(goal: Goal): Boolean = goal.name in GOAL_BASIS_CASH

fun goalValue(goal: Goal, s: FinancialStatement): Double =
    if (goalOnCash(goal)) s.cashChf else s.netWorthChf()

fun goalState(goal: Goal, s: FinancialStatement): GoalState = when {
    goalValue(goal, s) >= goal.targetChf -> GoalState.REACHED
    s.period.take(7) > goal.targetDate.take(7) -> GoalState.MISSED
    else -> GoalState.IN_PROGRESS
}
