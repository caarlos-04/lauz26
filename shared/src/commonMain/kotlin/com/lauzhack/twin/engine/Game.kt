package com.lauzhack.twin.engine

import com.lauzhack.twin.model.Attribution
import com.lauzhack.twin.model.AttributionItem
import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.CasePreview
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.EventCatalog
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.FlowModel
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.MacroFile
import com.lauzhack.twin.model.OptionPreview
import com.lauzhack.twin.model.StoryBeat
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.model.netWorthChf
import kotlinx.serialization.json.Json
import kotlin.math.round

// The game loop on statements: events change fields, the simulator moves time. Python reference: tools/game.py.

/** Everything the engine needs, loaded once (flow-model.json, macro.json, content/events.json). */
class GameEngine(
    val simulator: Simulator,
    val events: List<Event>,
    val config: GameConfig = GameConfig(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The engine from the texts of flow-model.json, macro.json and content/events.json (however the platform
         *  loads them). `macroJson = null` runs without trends (no growth of wages, rents or prices). */
        fun fromJson(flowModelJson: String, macroJson: String?, eventsJson: String,
                     rules: EngineRules = EngineRules(), config: GameConfig = GameConfig()): GameEngine =
            GameEngine(
                Simulator(json.decodeFromString<FlowModel>(flowModelJson),
                          macroJson?.let { json.decodeFromString<MacroFile>(it) }, rules),
                json.decodeFromString<EventCatalog>(eventsJson).events,
                config,
            )
    }

    /** Event of this turn for the statement at its start, or null for a quiet turn. Deterministic: a function of
     *  (statement, turn, events already used, seed). Apply [startTurn] to the statement first. */
    fun nextEvent(s: FinancialStatement, turn: Int, used: Set<String>, seed: Long): Event? =
        if (turn >= config.maxTurns) null else pickEvent(events, s, turn, used, seed)

    /** Life changes due at the start of the turn (they count for picking the event) and their story beats. */
    fun startTurn(s: FinancialStatement): Pair<FinancialStatement, List<StoryBeat>> {
        val next = applyDueLifeChanges(s)
        return next to beatsBetween(s, next)
    }

    /** Before choosing: every option x case, advanced one turn. */
    fun preview(s: FinancialStatement, event: Event): List<OptionPreview> = event.options.map { option ->
        OptionPreview(optionId = option.id, perCase = option.cases.mapValues { (_, case) ->
            val withEffects = applyEffects(s, case.effects, event.id)
            val months = simulator.run(withEffects, config.monthsPerTurn)
            CasePreview(prob = case.prob, stateAfter = months.last(),
                        deltaNetWorth = months.last().netWorthChf() - s.netWorthChf(),
                        minCash = months.minOf { it.cashChf },
                        pendingChanges = withEffects.pendingChanges - s.pendingChanges.toSet(),
                        narrative = case.narrative)
        })
    }

    /** The turn's case from the seeded luck (the same luck is used by the parallel lives). */
    fun drawCase(event: Event, optionId: String, seed: Long, turn: Int): Pair<CaseType, Double> {
        val luck = PortableRandom.turnLuck(seed, turn)
        return caseFromLuck(event.options.first { it.id == optionId }, luck) to luck
    }

    /** One turn from the statement at its start (after [startTurn]): the chosen option with the drawn case, then
     *  `monthsPerTurn` months; also the other option for each case (the counterfactual). */
    fun playTurn(s: FinancialStatement, turn: Int, event: Event?, optionId: String?, case: CaseType?, luck: Double,
                 lang: Lang = Lang.EN): TurnOutcome {
        val beats = mutableListOf<StoryBeat>()
        var state = s
        val counterfactual = mutableMapOf<CaseType, FinancialStatement>()
        if (event == null || optionId == null || case == null) {
            beats += beat(s, BeatKind.QUIET_PERIOD)
        } else {
            val option = event.options.first { it.id == optionId }
            val outcome = option.cases.getValue(case)
            beats += beat(s, BeatKind.DECISION, event.id, "option" to optionId, "label" to option.label.getValue(lang))
            beats += beat(s, BeatKind.CASE_RESOLVED, event.id, "case" to case.name, "luck" to format2(luck),
                          "narrative" to outcome.narrative.getValue(lang))
            state = applyEffects(s, outcome.effects, event.id)
            event.options.firstOrNull { it.id != optionId }?.let { other ->
                for ((c, otherCase) in other.cases) {
                    counterfactual[c] = simulator.run(applyEffects(s, otherCase.effects, event.id), config.monthsPerTurn).last()
                }
            }
        }
        val months = advance(state, config.monthsPerTurn, beats)
        val turnRecord = Turn(index = turn, eventId = event?.id, chosenOptionId = optionId, luck = luck,
                              resolvedCase = case, stateAfter = months.statements.last(),
                              counterfactual = counterfactual, beats = beats)
        return TurnOutcome(turnRecord, months.statements, months.attribution)
    }

    /** A whole game with fixed choices per turn ("A"/"B"): the reference flow used by the golden tests. */
    fun play(s0: FinancialStatement, choices: List<String>, seed: Long, lang: Lang = Lang.EN): GameRecord {
        val used = mutableSetOf<String>()
        val beats = mutableListOf(beat(s0, BeatKind.START, null, "cash" to roundToString(s0.cashChf)))
        val turns = mutableListOf<Turn>()
        val monthly = mutableListOf(s0)
        val attribution = linkedMapOf<String, Double>()
        var s = s0
        for (turn in 0 until config.maxTurns) {
            val (started, lifeBeats) = startTurn(s)
            beats += lifeBeats
            s = started
            val event = nextEvent(s, turn, used, seed)
            val luck = PortableRandom.turnLuck(seed, turn)
            val optionId = event?.let { choices.getOrElse(turn) { "A" } }
            val case = event?.let { caseFromLuck(it.options.first { o -> o.id == optionId }, luck) }
            event?.let { used += it.id }
            val outcome = playTurn(s, turn, event, optionId, case, luck, lang)
            beats += outcome.turn.beats
            outcome.attribution.forEach { (k, v) -> attribution[k] = (attribution[k] ?: 0.0) + v }
            monthly += outcome.months
            turns += outcome.turn
            s = outcome.turn.stateAfter
        }
        beats += beat(s, BeatKind.END, null, "netWorth" to roundToString(s.netWorthChf()), "cash" to roundToString(s.cashChf))
        return GameRecord(turns = turns, monthly = monthly, beats = beats,
                          attribution = Attribution(startNetWorth = s0.netWorthChf(),
                                                    items = attribution.map { (k, v) -> AttributionItem(k, v) },
                                                    endNetWorth = s.netWorthChf()))
    }

    // ------------------------------------------------------------------------------------------ months

    private class Months(val statements: List<FinancialStatement>, val attribution: Map<String, Double>)

    private fun advance(s: FinancialStatement, months: Int, beats: MutableList<StoryBeat>): Months {
        val out = mutableListOf<FinancialStatement>()
        val attribution = linkedMapOf<String, Double>()
        var prev = s
        repeat(months) {
            val next = simulator.step(prev)
            beats += beatsBetween(prev, next)
            addAttribution(attribution, prev, next)
            out += next
            prev = next
        }
        return Months(out, attribution)
    }
}

class TurnOutcome(val turn: Turn, val months: List<FinancialStatement>, val attribution: Map<String, Double>)

class GameRecord(val turns: List<Turn>, val monthly: List<FinancialStatement>, val beats: List<StoryBeat>,
                 val attribution: Attribution) {
    val statements: List<FinancialStatement> get() = listOf(monthly.first()) + turns.map { it.stateAfter }
    val decisions: List<String> get() = turns.mapNotNull { it.eventId }
}

// ---------------------------------------------------------------------------------------------- story and money

fun beat(s: FinancialStatement, kind: BeatKind, eventId: String? = null, vararg data: Pair<String, String>) =
    StoryBeat(month = s.month, age = s.person.age, kind = kind, eventId = eventId, data = linkedMapOf(*data))

/** Story moments of one month, read from two consecutive statements (structured data, no prose). */
fun beatsBetween(prev: FinancialStatement, cur: FinancialStatement): List<StoryBeat> {
    val out = mutableListOf<StoryBeat>()
    val p0 = prev.person
    val p1 = cur.person
    val due = prev.pendingChanges.filter { it.month <= prev.month }
    if (p1.employment != p0.employment) {
        out += beat(cur, BeatKind.LIFE_CHANGE, due.firstOrNull { it.employment == p1.employment }?.sourceEventId,
                    "employment" to p1.employment.name)
    }
    if (p1.household != p0.household) {
        out += beat(cur, BeatKind.LIFE_CHANGE, due.firstOrNull { it.household == p1.household }?.sourceEventId,
                    "household" to p1.household.name)
    }
    for ((k, v) in p1.facts) {
        if (p0.facts[k] != v) {
            out += beat(cur, BeatKind.LIFE_CHANGE, due.firstOrNull { it.factKey == k && it.factValue == v }?.sourceEventId,
                        "fact" to k.name, "value" to v)
        }
    }
    for (f in p1.flags) {
        if (f !in p0.flags) out += beat(cur, BeatKind.LIFE_CHANGE, due.firstOrNull { it.flag == f }?.sourceEventId, "flag" to f)
    }
    if (prev.cashChf >= 0 && cur.cashChf < 0) out += beat(cur, BeatKind.CASH_NEGATIVE, null, "cash" to roundToString(cur.cashChf))
    else if (prev.cashChf < 0 && cur.cashChf >= 0) out += beat(cur, BeatKind.CASH_RECOVERED, null, "cash" to roundToString(cur.cashChf))
    for (g in cur.goals) {
        val before = goalState(g, prev)
        val now = goalState(g, cur)
        if (now != before && before == GoalState.IN_PROGRESS && now != GoalState.IN_PROGRESS) {
            val kind = if (now == GoalState.REACHED) BeatKind.GOAL_REACHED else BeatKind.GOAL_MISSED
            out += beat(cur, kind, null, "goal" to g.name, "target" to roundToString(g.targetChf))
        }
    }
    return out
}

/** Where the net worth change of one month comes from: start + sum(items) = end. */
fun addAttribution(acc: MutableMap<String, Double>, prev: FinancialStatement, cur: FinancialStatement) {
    fun add(key: String, chf: Double) { acc[key] = (acc[key] ?: 0.0) + chf }
    val cf = cur.cashFlow ?: return
    for ((k, v) in cf.incomeChf) if (k != "loan") add("income:$k", v)   // a loan paid out raises cash and debt alike
    for ((k, v) in cf.expensesChf) add("expense:$k", -v)
    val repaid = (prev.loan?.principalChf ?: 0.0) + (cf.incomeChf["loan"] ?: 0.0) - (cur.loan?.principalChf ?: 0.0)
    if (cf.loanPaymentChf != 0.0) add("loan_interest", -(cf.loanPaymentChf - repaid))
    if (cf.financingCostChf != 0.0) add("financing_cost", -cf.financingCostChf)
    val growth = cur.investmentsChf - prev.investmentsChf
    if (growth != 0.0) add("investment_growth", growth)
}

private fun roundToString(x: Double): String = round(x).toLong().toString()

/** "0.50": two decimals, like Python's f"{x:.2f}" for the values in [0, 1) we print. */
private fun format2(x: Double): String {
    val cents = round(x * 100).toLong()
    return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
