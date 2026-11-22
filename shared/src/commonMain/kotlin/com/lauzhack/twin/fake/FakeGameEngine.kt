package com.lauzhack.twin.fake

import com.lauzhack.twin.engine.CashBand
import com.lauzhack.twin.engine.GameEngine
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.Attribution
import com.lauzhack.twin.model.AttributionItem
import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.CasePreview
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.EventOption
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.Goal
import com.lauzhack.twin.model.OptionPreview
import com.lauzhack.twin.model.PendingChange
import com.lauzhack.twin.model.ParallelKind
import com.lauzhack.twin.model.ParallelLife
import com.lauzhack.twin.model.Stats
import com.lauzhack.twin.model.StoryBeat
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.ui.basisChf
import com.lauzhack.twin.ui.cash
import com.lauzhack.twin.ui.current
import com.lauzhack.twin.ui.netWorth
import com.lauzhack.twin.ui.targetMonth
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * Simplified stand-in for Dev A's engine so the UI runs end to end on main's model.
 * Follows docs sections 6-7 loosely: event selection by requirements/priority, luck from (seed, turn),
 * 24-month turns, pending changes, moments, counterfactual, parallel lives (greedy, not expectimax) and a naive
 * noise band (#12). NOT calibrated. Replace by passing another [GameEngine] to AppState.
 */
class FakeGameEngine(
    private val catalog: List<Event>,
    private val assumptions: Assumptions,
) : GameEngine {

    private val ledger = FakeLedger(assumptions)
    private val eventsById = catalog.associateBy { it.id }

    // ---------- P3 ----------

    override fun nextEvent(session: GameSession): Event? {
        if (session.isOver) return null
        // Highest priority first; ties keep catalogue order (stories are written in order). Deterministic.
        return catalog.filter { isEligible(session, it) }.maxByOrNull { it.priority }
            ?.let { best -> catalog.first { it.priority == best.priority && isEligible(session, it) } }
    }

    override fun isEligible(session: GameSession, event: Event): Boolean {
        val s = session.current
        val used = session.turns.mapNotNull { it.eventId }
        if (event.earliestTurn > session.turns.size) return false
        if (!event.repeatable && event.id in used) return false
        val r = event.requires ?: return true
        val p = s.person
        return (r.employment == null || p.employment in r.employment) &&
            (r.minAge == null || p.age >= r.minAge) &&
            (r.maxAge == null || p.age <= r.maxAge) &&
            (r.facts == null || r.facts.all { (key, values) -> p.facts[key] in values }) &&
            (r.flagsAll == null || p.flags.containsAll(r.flagsAll)) &&
            (r.flagsNone == null || r.flagsNone.none { it in p.flags }) &&
            (r.minCashChf == null || s.cash >= r.minCashChf) &&
            (r.maxCashChf == null || s.cash <= r.maxCashChf)
    }

    // ---------- P4 ----------

    override fun preview(session: GameSession, event: Event): List<OptionPreview> {
        val before = session.current
        val months = monthsLeftInTurn(session)
        return event.options.map { option ->
            OptionPreview(
                optionId = option.id,
                perCase = option.cases.mapValues { (_, outcome) ->
                    val trajectory = ledger.advance(ledger.applyEffects(before, outcome.effects, event.id), months)
                    val after = trajectory.last()
                    CasePreview(
                        prob = outcome.prob,
                        stateAfter = after,
                        deltaNetWorth = after.netWorth - before.netWorth,
                        minCash = trajectory.minOf { it.cash },
                        pendingChanges = lifeChangesOf(outcome.effects, before.month, event.id),
                        narrative = outcome.narrative,
                    )
                },
            )
        }
    }

    // ---------- P6 ----------

    override fun resolveLuck(session: GameSession, option: EventOption): Pair<Double, CaseType> {
        val luck = luckAt(session.seed, session.turns.size)
        return luck to caseFor(option, luck)
    }

    // ---------- P7 ----------

    override fun playTurn(session: GameSession, event: Event?, optionId: String?, case: CaseType?, luck: Double): Turn {
        val before = session.current
        val months = monthsLeftInTurn(session)
        val index = session.turns.size
        if (event == null || optionId == null || case == null) {
            val trajectory = ledger.advance(before, months)
            val beats = listOf(StoryBeat(before.month, before.person.age, BeatKind.QUIET_PERIOD)) + monitor(trajectory, null, session.initial)
            return Turn(index, null, null, luck, null, trajectory.last(), emptyMap(), beats)
        }
        val chosen = event.options.first { it.id == optionId }
        val other = event.options.first { it.id != optionId }
        val trajectory = ledger.advance(ledger.applyEffects(before, chosen.cases.getValue(case).effects, event.id), months)
        val counterfactual = other.cases.mapValues { (_, outcome) ->
            ledger.advance(ledger.applyEffects(before, outcome.effects, event.id), months).last()
        }
        val age = before.person.age
        val beats = listOf(
            StoryBeat(before.month, age, BeatKind.DECISION, event.id, mapOf("option" to optionId)),
            StoryBeat(before.month, age, BeatKind.CASE_RESOLVED, event.id, mapOf("case" to case.name, "option" to optionId)),
        ) + monitor(trajectory, event.id, session.initial)
        return Turn(index, event.id, optionId, luck, case, trajectory.last(), counterfactual, beats)
    }

    // ---------- P8-P10 ----------

    override fun finish(session: GameSession): GameResult {
        val path = replay(session)
        val stats = statsOf(path.months, session)
        val start = session.initial
        val end = path.months.last()
        val beats = buildList {
            add(StoryBeat(0, start.person.age, BeatKind.START))
            session.turns.forEach { addAll(it.beats) }
            val low = path.months.minBy { it.cash }
            if (low.cash < start.cash) add(StoryBeat(low.month, low.person.age, BeatKind.LOW_POINT, data = mapOf("cash" to low.cash.toLong().toString())))
            end.goals.filter { !reached(it, path.months, start) }.forEach {
                add(StoryBeat(min(it.targetMonth(start), end.month), end.person.age, BeatKind.GOAL_MISSED, data = mapOf("goal" to it.name)))
            }
            add(StoryBeat(end.month, end.person.age, BeatKind.END))
        }.sortedBy { it.month }
        return GameResult(
            session = session,
            stats = stats,
            attribution = attribution(path.months),
            beats = beats,
            parallelLives = listOf(
                parallelLife(session, ParallelKind.OTHER_FIRST_CHOICE),
                parallelLife(session, ParallelKind.OPTIMAL_STRATEGY),
            ),
            sensitivity = emptyList(), // P10 belongs to the real engine.
        )
    }

    // ---------- internals ----------

    private fun monthsLeftInTurn(session: GameSession): Int =
        min(session.config.monthsPerTurn, session.config.horizonMonths - session.current.month).coerceAtLeast(0)

    private fun luckAt(seed: Long, turnIndex: Int): Double = Random(seed * 1_000_003L + turnIndex).nextDouble()

    /** First case (order P, N, O) whose cumulative probability exceeds [luck]. */
    private fun caseFor(option: EventOption, luck: Double): CaseType {
        var acc = 0.0
        for (case in CaseType.entries) {
            acc += option.cases[case]?.prob ?: assumptions.defaultCaseProbs[case] ?: 0.0
            if (luck < acc) return case
        }
        return CaseType.OPTIMISTIC
    }

    /** The life part of a case (work, facts, milestones, household), as the pending changes it will create. */
    private fun lifeChangesOf(effects: List<Effect>, month: Int, eventId: String): List<PendingChange> = effects
        .filter { it is Effect.SetEmployment || it is Effect.SetFact || it is Effect.AddFlag || it is Effect.SetHousehold }
        .flatMap { ledger.changesOf(it, month + 1, eventId) }

    /** Moments inside a turn: life changes, cash turning negative / recovering, goals reached. */
    private fun monitor(trajectory: List<FinancialStatement>, eventId: String?, opening: FinancialStatement): List<StoryBeat> {
        val out = mutableListOf<StoryBeat>()
        trajectory.zipWithNext { a, b ->
            if (a.cash >= 0 && b.cash < 0) out += StoryBeat(b.month, b.person.age, BeatKind.CASH_NEGATIVE, eventId)
            if (a.cash < 0 && b.cash >= 0) out += StoryBeat(b.month, b.person.age, BeatKind.CASH_RECOVERED, eventId)
            if (a.person != b.person) {
                (b.person.facts - a.person.facts.keys).plus(b.person.facts.filter { (k, v) -> a.person.facts[k] != v })
                    .forEach { (k, v) -> out += StoryBeat(b.month, b.person.age, BeatKind.LIFE_CHANGE, eventId, mapOf("fact" to k.name, "value" to v)) }
                (b.person.flags - a.person.flags.toSet()).forEach {
                    out += StoryBeat(b.month, b.person.age, BeatKind.LIFE_CHANGE, eventId, mapOf("flag" to it))
                }
                if (a.person.employment != b.person.employment) {
                    out += StoryBeat(b.month, b.person.age, BeatKind.LIFE_CHANGE, eventId, mapOf("employment" to b.person.employment.name))
                }
            }
            b.goals.forEach { g ->
                if (b.month <= g.targetMonth(opening) && g.basisChf(a) < g.targetChf && g.basisChf(b) >= g.targetChf) {
                    out += StoryBeat(b.month, b.person.age, BeatKind.GOAL_REACHED, data = mapOf("goal" to g.name))
                }
            }
        }
        return out
    }

    /** Month-by-month replay of a session with its own choices and cases. */
    private class Path(val months: List<FinancialStatement>)

    /** A goal counts as reached if its basis hits the target on or before its target month. */
    private fun reached(goal: Goal, months: List<FinancialStatement>, opening: FinancialStatement): Boolean {
        val deadline = goal.targetMonth(opening)
        return months.any { it.month <= deadline && goal.basisChf(it) >= goal.targetChf }
    }

    /**
     * start + items = end. Per month: income and expenses by category, the loan's interest, the financing cost and
     * the investments' growth (a loan's payout and the principal part of its payments do not change net worth).
     */
    private fun attribution(months: List<FinancialStatement>): Attribution {
        val items = linkedMapOf<String, Double>()
        fun add(key: String, chf: Double) { if (chf != 0.0) items[key] = (items[key] ?: 0.0) + chf }
        months.zipWithNext { a, b ->
            val f = b.cashFlow ?: return@zipWithNext
            f.incomeChf.forEach { (k, v) -> add("income:$k", v) }
            f.expensesChf.forEach { (k, v) -> add("expense:$k", -v) }
            add("financing_cost", -f.financingCostChf)
            val paidOut = a.pendingChanges.filter { it.month <= b.month }.sumOf { it.loan?.principalChf ?: 0.0 }
            val interest = (b.loan?.principalChf ?: 0.0) - ((a.loan?.principalChf ?: 0.0) + paidOut) + f.loanPaymentChf
            add("loan_interest", -interest)
            add("investment_growth", b.investmentsChf - a.investmentsChf)
        }
        val start = months.first().netWorth
        val end = months.last().netWorth
        val list = items.map { (k, v) -> AttributionItem(k, v) }
        val residual = end - start - list.sumOf { it.chf }
        val all = if (abs(residual) > 0.5) list + AttributionItem("other", residual) else list
        return Attribution(start, all.sortedByDescending { abs(it.chf) }, end)
    }

    private fun replay(session: GameSession): Path {
        val months = mutableListOf(session.initial)
        var s = session.copy(turns = emptyList())
        for (turn in session.turns) {
            val before = s.current
            val event = turn.eventId?.let { eventsById[it] }
            val start = if (event != null && turn.chosenOptionId != null && turn.resolvedCase != null) {
                val effects = event.options.first { it.id == turn.chosenOptionId }.cases.getValue(turn.resolvedCase).effects
                ledger.applyEffects(before, effects, event.id)
            } else before
            months += ledger.advance(start, monthsLeftInTurn(s)).drop(1)
            s = s.copy(turns = s.turns + turn)
        }
        return Path(months)
    }

    private fun statsOf(months: List<FinancialStatement>, session: GameSession): Stats {
        val low = months.minBy { it.cash }
        val end = months.last()
        val active = months.drop(1)
        return Stats(
            finalNetWorth = end.netWorth,
            minCash = low.cash,
            monthOfMinCash = low.month,
            monthsNegativeCash = active.count { it.cash < 0 },
            monthsBelowMinCash = active.count { it.cash < session.preferences.minCashChf },
            financingCostChf = active.sumOf { it.cashFlow?.financingCostChf ?: 0.0 },
            goalsReached = end.goals.filter { reached(it, months, session.initial) }.map { it.name },
            goalsMissed = end.goals.filter { !reached(it, months, session.initial) }.map { it.name },
        )
    }

    /** Greedy stand-in for the expectimax "best option": expected net worth after the turn, minus the cushion penalty. */
    private fun bestOption(session: GameSession, event: Event): String = preview(session, event).maxBy { p ->
        p.perCase.values.sumOf { c ->
            val penalty = if (c.minCash < session.preferences.minCashChf) session.preferences.penaltyChfPerMonthBelowMin * session.config.monthsPerTurn else 0.0
            c.prob * (c.deltaNetWorth - penalty)
        }
    }.optionId

    /** Replays the game with the same luck per turn: first decision flipped (or not) and the greedy strategy after. */
    private fun parallelLife(session: GameSession, kind: ParallelKind): ParallelLife {
        var s = session.copy(turns = emptyList())
        while (!s.isOver) {
            val i = s.turns.size
            val luck = session.turns.getOrNull(i)?.luck ?: luckAt(session.seed, i)
            val event = nextEvent(s)
            s = if (event == null) {
                s.copy(turns = s.turns + playTurn(s, null, null, null, luck))
            } else {
                val played = session.turns.getOrNull(i)
                val optionId = if (kind == ParallelKind.OTHER_FIRST_CHOICE && i == 0 && played?.eventId == event.id)
                    event.options.first { it.id != played.chosenOptionId }.id
                else bestOption(s, event)
                val option = event.options.first { it.id == optionId }
                s.copy(turns = s.turns + playTurn(s, event, optionId, caseFor(option, luck), luck))
            }
        }
        val path = replay(s)
        return ParallelLife(kind, s.turns, path.months.last(), statsOf(path.months, s), attribution(path.months))
    }

    /**
     * #12 option 2: cash with the played choices and cases under month-to-month noise (and a small return shock).
     * Only "how much noise moves your cash"; the analysis is the decision tree and the parallel lives.
     */
    override fun noiseBand(session: GameSession): CashBand = noiseBand(session, 200)

    private fun noiseBand(session: GameSession, nPaths: Int): CashBand {
        val horizon = session.config.horizonMonths
        val rng = Random(session.seed + 7919L)
        val paths = List(nPaths) {
            val shock = (rng.nextDouble() - 0.5) * 0.04
            val noise: (FinancialStatement) -> Double = { st ->
                val sd = 0.10 * st.monthlyIncomeChf + 60.0
                (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 1.5) * sd
            }
            val cash = mutableListOf(session.initial.cash)
            var state = session.initial
            for (turn in session.turns) {
                val event = turn.eventId?.let { eventsById[it] }
                val option = event?.options?.firstOrNull { it.id == turn.chosenOptionId }
                val case = turn.resolvedCase
                val applied = if (event != null && option != null && case != null)
                    ledger.applyEffects(state, option.cases.getValue(case).effects, event.id)
                else state
                val months = min(session.config.monthsPerTurn, horizon - state.month)
                val traj = ledger.advance(applied, months, noise, shock)
                cash += traj.drop(1).map { it.cash }
                state = traj.last()
            }
            while (cash.size < horizon + 1) cash += cash.last()
            cash
        }
        fun pct(m: Int, q: Double): Double {
            val sorted = paths.map { it[m] }.sorted()
            return sorted[((sorted.size - 1) * q).toInt()]
        }
        return CashBand(
            p10 = (0..horizon).map { pct(it, 0.10) },
            p50 = (0..horizon).map { pct(it, 0.50) },
            p90 = (0..horizon).map { pct(it, 0.90) },
        )
    }
}
