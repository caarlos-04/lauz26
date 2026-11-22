package com.lauzhack.twin.engine

import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.Attribution
import com.lauzhack.twin.model.AttributionItem
import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.EventCatalog
import com.lauzhack.twin.model.EventOption
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.FlowModel
import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.MacroFile
import com.lauzhack.twin.model.OptionPreview
import com.lauzhack.twin.model.ParallelKind
import com.lauzhack.twin.model.ParallelLife
import com.lauzhack.twin.model.Stats
import com.lauzhack.twin.model.StoryBeat
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.model.netWorthChf
import kotlin.math.floor
import kotlin.random.Random

/**
 * The UI's [GameEngine] on top of the real engine ([TwinEngine], the port of tools/game.py) (#16).
 *
 * Stateless: every call is a pure function of the [GameSession] (initial statement, seed, the turns played), so the
 * what-if tree can replay any path. The state at the start of a turn is the last `stateAfter` with its due life
 * changes applied ([TwinEngine.startTurn]); turn i uses the seeded luck of turn i, as in the golden games.
 */
class RealGameEngine(private val twin: TwinEngine) : GameEngine {

    private val byId = twin.events.associateBy { it.id }

    // ------------------------------------------------------------------------------------------ the turn

    private fun engineFor(session: GameSession): TwinEngine =
        if (session.config == twin.config) twin else TwinEngine(twin.simulator, twin.events, session.config)

    private fun current(session: GameSession): FinancialStatement = session.turns.lastOrNull()?.stateAfter ?: session.initial

    private fun used(session: GameSession): Set<String> = session.turns.mapNotNull { it.eventId }.toSet()

    /** The statement at the start of the next turn (due life changes applied) and the moments they create. */
    private fun started(session: GameSession): Pair<FinancialStatement, List<StoryBeat>> = engineFor(session).startTurn(current(session))

    override fun nextEvent(session: GameSession): Event? =
        engineFor(session).nextEvent(started(session).first, session.turns.size, used(session), session.seed)

    override fun isEligible(session: GameSession, event: Event): Boolean =
        com.lauzhack.twin.engine.isEligible(event, started(session).first, session.turns.size, used(session))

    override fun preview(session: GameSession, event: Event): List<OptionPreview> = engineFor(session).preview(started(session).first, event)

    override fun resolveLuck(session: GameSession, option: EventOption): Pair<Double, CaseType> {
        val luck = PortableRandom.turnLuck(session.seed, session.turns.size)
        return luck to caseFromLuck(option, luck)
    }

    override fun playTurn(session: GameSession, event: Event?, optionId: String?, case: CaseType?, luck: Double): Turn {
        val (s, lifeBeats) = started(session)
        val outcome = engineFor(session).playTurn(s, session.turns.size, event, optionId, case, luck)
        return outcome.turn.copy(beats = lifeBeats + outcome.turn.beats)
    }

    // ------------------------------------------------------------------------------------------ the end

    override fun finish(session: GameSession): GameResult {
        val path = replay(session)
        val start = session.initial
        val low = path.monthly.minBy { it.cashChf }
        val end = path.monthly.last()
        val beats = buildList {
            add(beat(start, BeatKind.START, null, "cash" to whole(start.cashChf)))
            addAll(path.beats)
            if (low.cashChf < start.cashChf) add(beat(low, BeatKind.LOW_POINT, null, "cash" to whole(low.cashChf)))
            add(beat(end, BeatKind.END, null, "netWorth" to whole(end.netWorthChf()), "cash" to whole(end.cashChf)))
        }.sortedBy { it.month }                       // stable: the order inside a month is kept
        return GameResult(
            session = session,
            stats = statsOf(path.monthly, session),
            attribution = path.attribution,
            beats = beats,
            parallelLives = listOf(parallelLife(session, ParallelKind.OTHER_FIRST_CHOICE), parallelLife(session, ParallelKind.OPTIMAL_STRATEGY)),
            sensitivity = emptyList(),                // P10 (break-even analysis) is not implemented yet
        )
    }

    /**
     * #12 option 2: p10 / p50 / p90 of cash per month, replaying the played choices and cases with the simulator's
     * sampled monthly flows. Only "how much month-to-month noise moves your cash"; not the analysis.
     */
    override fun noiseBand(session: GameSession): CashBand = noiseBand(session, NOISE_PATHS)

    fun noiseBand(session: GameSession, paths: Int): CashBand {
        val engine = engineFor(session)
        val rng = Random(session.seed + NOISE_SALT)
        val horizon = session.config.horizonMonths
        val cash = List(paths) {
            val out = mutableListOf(session.initial.cashChf)
            var state = session.initial
            for (turn in session.turns) {
                var s = engine.startTurn(state).first
                val event = turn.eventId?.let { byId[it] }
                val outcome = event?.options?.firstOrNull { it.id == turn.chosenOptionId }?.cases?.get(turn.resolvedCase)
                if (event != null && outcome != null) s = applyEffects(s, outcome.effects, event.id)
                val months = engine.simulator.run(s, session.config.monthsPerTurn, SimMode.SAMPLE, rng).drop(1)
                months.forEach { out += it.cashChf }
                state = months.lastOrNull() ?: s
            }
            while (out.size <= horizon) out += out.last()
            out.take(horizon + 1)
        }
        fun pct(p: Double) = List(horizon + 1) { m -> percentile(cash.map { it[m] }, p) }
        return CashBand(p10 = pct(0.1), p50 = pct(0.5), p90 = pct(0.9))
    }

    // ------------------------------------------------------------------------------------------ replays

    private class Path(val monthly: List<FinancialStatement>, val beats: List<StoryBeat>, val attribution: Attribution)

    /** The session's turns again, month by month (same events, options, cases and luck): statements, beats and attribution. */
    private fun replay(session: GameSession): Path {
        val engine = engineFor(session)
        val monthly = mutableListOf(session.initial)
        val beats = mutableListOf<StoryBeat>()
        val items = linkedMapOf<String, Double>()
        var state = session.initial
        session.turns.forEachIndexed { i, turn ->
            val (s, life) = engine.startTurn(state)
            val outcome = engine.playTurn(s, i, turn.eventId?.let { byId[it] }, turn.chosenOptionId, turn.resolvedCase, turn.luck)
            beats += life
            beats += outcome.turn.beats
            outcome.attribution.forEach { (k, v) -> items[k] = (items[k] ?: 0.0) + v }
            monthly += outcome.months
            state = outcome.turn.stateAfter
        }
        val attribution = Attribution(session.initial.netWorthChf(), items.map { (k, v) -> AttributionItem(k, v) }, state.netWorthChf())
        return Path(monthly, beats, attribution)
    }

    /**
     * The same luck per turn, with the first decision flipped (OTHER_FIRST_CHOICE) or not, and a greedy strategy for
     * every other decision (expected change of net worth minus the cushion penalty): a stand-in for the expectimax tree.
     */
    private fun parallelLife(session: GameSession, kind: ParallelKind): ParallelLife {
        val engine = engineFor(session)
        val firstDecision = session.turns.indexOfFirst { it.eventId != null }
        var life = session.copy(turns = emptyList())
        while (!life.isOver) {
            val i = life.turns.size
            val luck = session.turns.getOrNull(i)?.luck ?: PortableRandom.turnLuck(session.seed, i)
            val event = nextEvent(life)
            val played = session.turns.getOrNull(i)
            val turn = if (event == null) {
                playTurn(life, null, null, null, luck)
            } else {
                val optionId = if (kind == ParallelKind.OTHER_FIRST_CHOICE && i == firstDecision && played?.eventId == event.id)
                    event.options.first { it.id != played.chosenOptionId }.id
                else bestOption(life, event)
                playTurn(life, event, optionId, caseFromLuck(event.options.first { it.id == optionId }, luck), luck)
            }
            life = life.copy(turns = life.turns + turn)
        }
        val path = replay(life)
        return ParallelLife(kind, life.turns, path.monthly.last(), statsOf(path.monthly, life), path.attribution)
    }

    private fun bestOption(session: GameSession, event: Event): String {
        val months = session.config.monthsPerTurn
        val prefs = session.preferences
        return preview(session, event).maxBy { p ->
            p.perCase.values.sumOf { c ->
                val penalty = if (c.minCash < prefs.minCashChf) prefs.penaltyChfPerMonthBelowMin * months else 0.0
                c.prob * (c.deltaNetWorth - penalty)
            }
        }.optionId
    }

    private fun statsOf(monthly: List<FinancialStatement>, session: GameSession): Stats {
        val low = monthly.minBy { it.cashChf }
        val end = monthly.last()
        val active = monthly.drop(1)
        val reached = end.goals.filter { g -> monthly.any { goalState(g, it) == GoalState.REACHED } }.map { it.name }
        return Stats(
            finalNetWorth = end.netWorthChf(),
            minCash = low.cashChf,
            monthOfMinCash = low.month,
            monthsNegativeCash = active.count { it.cashChf < 0 },
            monthsBelowMinCash = active.count { it.cashChf < session.preferences.minCashChf },
            financingCostChf = active.sumOf { it.cashFlow?.financingCostChf ?: 0.0 },
            goalsReached = reached,
            goalsMissed = end.goals.map { it.name }.filter { it !in reached },
        )
    }

    companion object {
        const val NOISE_PATHS = 200
        private const val NOISE_SALT = 7919L

        /** The engine from the packaged content (AppContent): flow model, macro trends, events and assumptions. */
        fun from(flowModel: FlowModel, macro: MacroFile?, events: EventCatalog, assumptions: Assumptions): RealGameEngine {
            val rules = EngineRules(financingRateAnnual = assumptions.financingRateAnnual, investmentReturnAnnual = assumptions.investmentReturnAnnual)
            return RealGameEngine(TwinEngine(Simulator(flowModel, macro, rules), events.events))
        }

        private fun whole(x: Double): String = kotlin.math.round(x).toLong().toString()

        /** Linear interpolation between order statistics (as Python's statistics on sorted values). */
        private fun percentile(values: List<Double>, p: Double): Double {
            val xs = values.sorted()
            val k = (xs.size - 1) * p
            val lo = floor(k).toInt()
            val hi = minOf(lo + 1, xs.size - 1)
            return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)
        }
    }
}
