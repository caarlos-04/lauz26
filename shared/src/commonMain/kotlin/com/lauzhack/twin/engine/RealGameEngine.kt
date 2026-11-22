package com.lauzhack.twin.engine

import com.lauzhack.twin.model.Action
import com.lauzhack.twin.model.ActionCatalog
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.Attribution
import com.lauzhack.twin.model.AttributionItem
import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.DecisionTree
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.FlowModel
import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.MacroFile
import com.lauzhack.twin.model.Stats
import com.lauzhack.twin.model.StoryBeat
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.model.netWorthChf
import kotlin.math.floor
import kotlin.random.Random

/**
 * The UI's [GameEngine] on top of the real engine ([TwinEngine], the port of tools/game.py).
 *
 * Stateless: every call is a pure function of the [GameSession] (initial statement, seed, luck mode and the decisions
 * taken), so the decision tree can rebuild any node. The state at the start of a turn is the last `stateAfter` with
 * its due life changes applied ([TwinEngine.startTurn]).
 */
class RealGameEngine(private val twin: TwinEngine) : GameEngine {

    private val byId: Map<String, Action> = twin.actions.associateBy { it.id }
    private val shocksById = twin.shocks.associateBy { it.id }

    // ------------------------------------------------------------------------------------------ a decision

    private fun engineFor(session: GameSession): TwinEngine =
        if (session.config == twin.config) twin else TwinEngine(twin.simulator, twin.actions, twin.shocks, session.config)

    private fun last(session: GameSession): FinancialStatement = session.turns.lastOrNull()?.stateAfter ?: session.initial

    /** The statement at the start of the next turn (due life changes applied) and the moments they create. */
    private fun started(session: GameSession): Pair<FinancialStatement, List<StoryBeat>> = engineFor(session).startTurn(last(session))

    override fun actionsAt(session: GameSession): List<Action> =
        engineFor(session).actionsAt(started(session).first, session.turns.size, session.usedActions)

    override fun decide(session: GameSession, actionId: String): Turn {
        check(!session.isOver) { "The game is over: no more decisions" }
        val (s, lifeBeats) = started(session)
        val outcome = engineFor(session).playTurn(s, session.turns.size, session.nodeId, actionId, session.usedActions,
                                                  session.seed, session.luckMode)
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
            sensitivity = emptyList(),                // P10 (break-even analysis) is not implemented yet
        )
    }

    /** Only the replay: cheap enough to explain every node. */
    override fun attribution(session: GameSession): Attribution = replay(session).attribution

    /**
     * #12 option 2: p10 / p50 / p90 of cash per month, replaying the decisions (same cases and shocks) with the
     * simulator's sampled monthly flows. Only "how much month-to-month noise moves your cash"; not the analysis.
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
                engine.action(turn.actionId)?.let { s = applyEffects(s, resolve(it, turn.luck).effects, turn.actionId) }
                val shock = turn.shockId?.let { shocksById[it] }
                if (shock != null) s = applyEffects(s, shifted(resolve(shock, turn.shockLuck ?: 0.5).effects, turn.shockMonth ?: 0), turn.shockId)
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

    /** The session's decisions again, month by month (pure: the same cases, luck and shocks): statements, beats and attribution. */
    private fun replay(session: GameSession): Path {
        val engine = engineFor(session)
        val monthly = mutableListOf(session.initial)
        val beats = mutableListOf<StoryBeat>()
        val items = linkedMapOf<String, Double>()
        val used = mutableSetOf<String>()
        var node = DecisionTree.ROOT
        var state = session.initial
        session.turns.forEachIndexed { i, turn ->
            val (s, life) = engine.startTurn(state)
            val outcome = engine.playTurn(s, i, node, turn.actionId, used, session.seed, session.luckMode)
            used += turn.actionId
            node = DecisionTree.childId(node, turn.actionId)
            beats += life
            beats += outcome.turn.beats
            outcome.attribution.forEach { (k, v) -> items[k] = (items[k] ?: 0.0) + v }
            monthly += outcome.months
            state = outcome.turn.stateAfter
        }
        val attribution = Attribution(session.initial.netWorthChf(), items.map { (k, v) -> AttributionItem(k, v) }, state.netWorthChf())
        return Path(monthly, beats, attribution)
    }

    private fun statsOf(monthly: List<FinancialStatement>, session: GameSession): Stats {
        val low = monthly.minBy { it.cashChf }
        val end = monthly.last()
        val active = monthly.drop(1)
        // Reached = the target was hit by its deadline (hitting it after the deadline does not undo a missed goal).
        val reached = end.goals.filter { g -> monthly.any { goalValue(g, it) >= g.targetChf && it.period.take(7) <= g.targetDate.take(7) } }.map { it.name }
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

        /** The engine from the packaged content (AppContent): flow model, macro trends, actions and assumptions. */
        fun from(flowModel: FlowModel, macro: MacroFile?, catalog: ActionCatalog, assumptions: Assumptions): RealGameEngine {
            val rules = EngineRules(financingRateAnnual = assumptions.financingRateAnnual, investmentReturnAnnual = assumptions.investmentReturnAnnual)
            return RealGameEngine(TwinEngine(Simulator(flowModel, macro, rules), catalog.actions, catalog.shocks))
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
