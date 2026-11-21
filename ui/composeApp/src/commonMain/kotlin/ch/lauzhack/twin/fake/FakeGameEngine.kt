package ch.lauzhack.twin.fake

import ch.lauzhack.twin.engine.GameEngine
import ch.lauzhack.twin.engine.PathKind
import ch.lauzhack.twin.model.Assumptions
import ch.lauzhack.twin.model.CaseType
import ch.lauzhack.twin.model.Effect
import ch.lauzhack.twin.model.Event
import ch.lauzhack.twin.model.EventOption
import ch.lauzhack.twin.model.ExpenseStream
import ch.lauzhack.twin.model.FanBand
import ch.lauzhack.twin.model.FinancialState
import ch.lauzhack.twin.model.GameSession
import ch.lauzhack.twin.model.IncomeStream
import ch.lauzhack.twin.model.Lang
import ch.lauzhack.twin.model.OptionPreview
import ch.lauzhack.twin.model.CasePreview
import ch.lauzhack.twin.model.PathComparison
import ch.lauzhack.twin.model.SimulationResult
import ch.lauzhack.twin.model.Stats
import ch.lauzhack.twin.model.Turn
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Simplified stand-in for Dev A's engine so the UI can run end to end.
 * Follows the monthly rules of docs section 7 loosely; the Monte Carlo is a naive placeholder
 * (random case per turn + uniform-ish monthly noise), NOT the calibrated bootstrap.
 * Replace with the real engine by passing another [GameEngine] to AppState.
 */
class FakeGameEngine(
    private val catalog: List<Event>,
    private val assumptions: Assumptions,
) : GameEngine {

    private val eventsById = catalog.associateBy { it.id }

    override fun nextEvent(session: GameSession): Event? {
        val turnIndex = session.turns.size
        if (turnIndex >= session.config.maxTurns) return null
        val used = session.turns.map { it.eventId }.toSet()
        return catalog.firstOrNull { e -> e.id !in used && isEligible(session, e) }
    }

    override fun isEligible(session: GameSession, event: Event): Boolean =
        event.earliestTurn <= session.turns.size && matches(event, session, currentState(session))

    override fun preview(session: GameSession, event: Event, lang: Lang): List<OptionPreview> {
        val before = currentState(session)
        return event.options.map { option ->
            OptionPreview(
                optionId = option.id,
                perCase = option.cases.mapValues { (_, outcome) ->
                    val trajectory = advance(applyEffects(before, outcome.effects), session.config.monthsPerTurn)
                    val after = trajectory.last()
                    CasePreview(
                        prob = outcome.prob,
                        stateAfter = after,
                        deltaNetWorth = after.netWorth - before.netWorth,
                        minCash = trajectory.minOf { it.cash },
                        narrative = outcome.narrative[lang] ?: outcome.narrative.values.firstOrNull().orEmpty(),
                    )
                },
            )
        }
    }

    override fun drawCase(option: EventOption, seed: Long, turnIndex: Int): CaseType =
        drawCase(option, Random(seed + turnIndex))

    override fun playTurn(session: GameSession, event: Event, optionId: String, case: CaseType): Turn {
        val before = currentState(session)
        val months = session.config.monthsPerTurn
        val chosen = event.options.first { it.id == optionId }
        val other = event.options.first { it.id != optionId }
        val after = advance(applyEffects(before, chosen.cases.getValue(case).effects), months).last()
        val counterfactual = other.cases.mapValues { (_, outcome) ->
            advance(applyEffects(before, outcome.effects), months).last()
        }
        return Turn(
            index = session.turns.size,
            eventId = event.id,
            chosenOptionId = optionId,
            resolvedCase = case,
            stateBefore = before,
            stateAfter = after,
            counterfactual = counterfactual,
        )
    }

    override fun simulate(session: GameSession, path: PathKind, nPaths: Int): SimulationResult {
        val horizon = session.config.horizonMonths
        val seed = session.seed + 7919L * (path.ordinal + 1)
        val rng = Random(seed)
        val cashPaths = ArrayList<List<Double>>(nPaths)
        val worthPaths = ArrayList<List<Double>>(nPaths)
        repeat(nPaths) {
            // Perturb investment returns once per path so the fan does not collapse.
            val returnShock = (rng.nextDouble() - 0.5) * 0.04
            val traj = trajectory(
                session = session,
                path = path,
                caseFor = { _, option -> drawCase(option, rng) },
                noise = { state ->
                    val gross = state.income.sumOf { it.monthlyChf }
                    val sd = 0.12 * gross + 60.0
                    // Sum of 3 uniforms ~ bell-shaped noise, mean 0.
                    (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 1.5) * sd
                },
                returnShock = returnShock,
            )
            cashPaths += traj.map { it.cash }
            worthPaths += traj.map { it.netWorth }
        }
        val goals = session.profile.goals
        return SimulationResult(
            nPaths = nPaths,
            seed = seed,
            horizonMonths = horizon,
            cash = fan(cashPaths, horizon),
            netWorth = fan(worthPaths, horizon),
            probNegativeCash = cashPaths.count { p -> p.any { it < 0 } }.toDouble() / nPaths,
            goalProbabilities = goals.associate { g ->
                val m = min(g.targetMonth, horizon)
                g.id to worthPaths.count { it[m] >= g.targetChf }.toDouble() / nPaths
            },
            goalMedianMonth = goals.associate { g ->
                val hits = worthPaths.mapNotNull { p -> p.indexOfFirst { it >= g.targetChf }.takeIf { it >= 0 } }
                g.id to if (hits.size * 2 >= nPaths) hits.sorted()[hits.size / 2] else null
            },
        )
    }

    override fun stats(session: GameSession, path: PathKind): Stats {
        val traj = trajectory(session, path)
        val minState = traj.minBy { it.cash }
        val (reached, missed) = session.profile.goals.partition { g ->
            traj[min(g.targetMonth, traj.lastIndex)].netWorth >= g.targetChf
        }
        return Stats(
            finalNetWorth = traj.last().netWorth,
            minCash = minState.cash,
            monthOfMinCash = minState.month,
            monthsNegativeCash = traj.count { it.cash < 0 },
            goalsReached = reached.map { it.id },
            goalsMissed = missed.map { it.id },
        )
    }

    override fun compare(session: GameSession): PathComparison {
        val chosen = stats(session, PathKind.CHOSEN)
        val alternative = stats(session, PathKind.ALTERNATIVE)
        val explanation = session.turns
            .sortedByDescending { t -> abs(t.stateAfter.netWorth - t.counterfactual.getValue(t.resolvedCase).netWorth) }
            .map { it.eventId }
        return PathComparison(
            chosen = chosen,
            alternative = alternative,
            deltaFinalNetWorth = chosen.finalNetWorth - alternative.finalNetWorth,
            explanation = explanation,
        )
    }

    // ---------- internals ----------

    private fun currentState(session: GameSession): FinancialState =
        session.turns.lastOrNull()?.stateAfter ?: session.initial

    private fun matches(event: Event, session: GameSession, state: FinancialState): Boolean {
        val r = event.requires ?: return true
        val age = session.profile.ageAtStart + state.month / 12
        return (r.employment == null || state.employment in r.employment) &&
            (r.household == null || session.profile.household in r.household) &&
            (r.minAge == null || age >= r.minAge) &&
            (r.maxAge == null || age <= r.maxAge) &&
            (r.minCash == null || state.cash >= r.minCash) &&
            (r.requiresLiability == null || state.liabilities.isNotEmpty() == r.requiresLiability)
    }

    private fun drawCase(option: EventOption, rng: Random): CaseType {
        val x = rng.nextDouble()
        var acc = 0.0
        for (case in CaseType.entries) {
            acc += option.cases[case]?.prob ?: assumptions.defaultCaseProbs[case] ?: 0.0
            if (x < acc) return case
        }
        return CaseType.NEUTRAL
    }

    /**
     * Full monthly trajectory (size horizon + 1) replaying the session's events.
     * CHOSEN uses the chosen option, ALTERNATIVE the other one; both with the same case unless [caseFor] overrides it.
     */
    private fun trajectory(
        session: GameSession,
        path: PathKind,
        caseFor: ((Turn, EventOption) -> CaseType)? = null,
        noise: ((FinancialState) -> Double)? = null,
        returnShock: Double = 0.0,
    ): List<FinancialState> {
        val horizon = session.config.horizonMonths
        var state = session.initial.let { s ->
            s.copy(assets = s.assets.map { it.copy(annualReturn = it.annualReturn + returnShock) })
        }
        val out = mutableListOf(state)
        for (turn in session.turns) {
            val event = eventsById[turn.eventId] ?: continue
            val option = event.options.first {
                if (path == PathKind.CHOSEN) it.id == turn.chosenOptionId else it.id != turn.chosenOptionId
            }
            val case = caseFor?.invoke(turn, option) ?: turn.resolvedCase
            val months = min(session.config.monthsPerTurn, horizon - state.month)
            if (months <= 0) break
            val traj = advance(applyEffects(state, option.cases.getValue(case).effects), months, noise)
            out += traj.drop(1)
            state = traj.last()
        }
        if (state.month < horizon) out += advance(state, horizon - state.month, noise).drop(1)
        return out
    }

    /** Applies the effects of a case at the start of a turn (docs section 7, step 1). */
    internal fun applyEffects(state: FinancialState, effects: List<Effect>): FinancialState =
        effects.fold(state) { s, effect ->
            val m = s.month
            when (effect) {
                is Effect.OneOffCost -> s.copy(cash = s.cash - effect.chf)
                is Effect.SetEmployment -> s.copy(employment = effect.employment)
                is Effect.AddLiability -> s.copy(liabilities = s.liabilities + effect.liability)
                is Effect.AssetReturnDelta -> s.copy(assets = s.assets.map {
                    if (it.type == effect.assetType) it.copy(annualReturn = it.annualReturn + effect.deltaAnnualRate) else it
                })
                is Effect.IncomeDelta -> {
                    val existing = s.income.firstOrNull { it.id == effect.streamId }
                    if (effect.months == null && existing != null) {
                        s.copy(income = s.income.map {
                            if (it.id == effect.streamId) it.copy(monthlyChf = max(0.0, it.monthlyChf + effect.deltaChf)) else it
                        })
                    } else {
                        val id = if (existing == null) effect.streamId else "${effect.streamId}@$m"
                        s.copy(income = s.income + IncomeStream(id, effect.streamId, effect.deltaChf, effect.months?.let { m + it }))
                    }
                }
                is Effect.ExpenseDelta -> {
                    val baseId = effect.category.name.lowercase()
                    val existing = s.expenses.firstOrNull { it.id == baseId }
                    if (effect.months == null && existing != null) {
                        s.copy(expenses = s.expenses.map {
                            if (it.id == baseId) it.copy(monthlyChf = max(0.0, it.monthlyChf + effect.deltaChf)) else it
                        })
                    } else {
                        val id = if (existing == null) baseId else "$baseId@$m"
                        s.copy(expenses = s.expenses + ExpenseStream(id, effect.category, effect.deltaChf, effect.months?.let { m + it }))
                    }
                }
            }
        }

    /** Monthly steps (docs section 7, step 2). Returns [start, month1, ..., monthN]. */
    internal fun advance(
        start: FinancialState,
        months: Int,
        noise: ((FinancialState) -> Double)? = null,
    ): List<FinancialState> {
        val out = ArrayList<FinancialState>(months + 1)
        out += start
        var s = start
        repeat(months) {
            val t = s.month
            val income = s.income.filter { it.untilMonth == null || t < it.untilMonth }
            val expenses = s.expenses.filter { it.untilMonth == null || t < it.untilMonth }
            var debtPayment = 0.0
            val liabilities = s.liabilities.map { l ->
                if (l.principalChf <= 0.0) return@map l
                val owed = l.principalChf * (1 + l.annualRate / 12)
                val pay = min(l.monthlyPaymentChf, owed)
                debtPayment += pay
                l.copy(principalChf = max(0.0, owed - pay))
            }
            val net = income.sumOf { it.monthlyChf } - expenses.sumOf { it.monthlyChf } - debtPayment
            val next = t + 1
            s = s.copy(
                month = next,
                cash = s.cash + net + (noise?.invoke(s) ?: 0.0),
                assets = s.assets.map { it.copy(valueChf = it.valueChf * (1 + it.annualReturn / 12)) },
                liabilities = liabilities,
                // Salary growth and inflation applied monthly to the stream amounts; expired streams are dropped.
                income = income.filter { it.untilMonth == null || next < it.untilMonth }
                    .map { it.copy(monthlyChf = it.monthlyChf * (1 + assumptions.salaryGrowthAnnual / 12)) },
                expenses = expenses.filter { it.untilMonth == null || next < it.untilMonth }
                    .map { it.copy(monthlyChf = it.monthlyChf * (1 + assumptions.inflationAnnual / 12)) },
            )
            out += s
        }
        return out
    }

    private fun fan(paths: List<List<Double>>, horizon: Int): FanBand {
        val p10 = ArrayList<Double>(horizon + 1)
        val p50 = ArrayList<Double>(horizon + 1)
        val p90 = ArrayList<Double>(horizon + 1)
        for (m in 0..horizon) {
            val sorted = paths.map { it[m] }.sorted()
            p10 += percentile(sorted, 0.10)
            p50 += percentile(sorted, 0.50)
            p90 += percentile(sorted, 0.90)
        }
        return FanBand(p10, p50, p90)
    }

    private fun percentile(sorted: List<Double>, q: Double): Double =
        sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)]
}
