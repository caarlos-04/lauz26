package com.lauzhack.twin.engine

import com.lauzhack.twin.model.Action
import com.lauzhack.twin.model.ActionCatalog
import com.lauzhack.twin.model.Attribution
import com.lauzhack.twin.model.AttributionItem
import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.DecisionTree
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.FlowModel
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.LuckMode
import com.lauzhack.twin.model.MacroFile
import com.lauzhack.twin.model.NOTHING
import com.lauzhack.twin.model.Shock
import com.lauzhack.twin.model.StoryBeat
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.model.netWorthChf
import kotlinx.serialization.json.Json
import kotlin.math.round

// The game on statements: the player picks an action at each node of the decision tree, the simulator moves time
// (docs/decision-tree.md). Python reference: tools/game.py.
// TwinEngine is the real engine; the UI talks to the GameEngine interface (engine/GameEngine.kt), implemented on top
// of it by RealGameEngine.

/** Everything the engine needs, loaded once (flow-model.json, macro.json, content/events.json). */
class TwinEngine(
    val simulator: Simulator,
    val actions: List<Action>,
    val shocks: List<Shock> = emptyList(),
    val config: GameConfig = GameConfig(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The engine from the texts of flow-model.json, macro.json and content/events.json (however the platform
         *  loads them). `macroJson = null` runs without trends (no growth of wages, rents or prices). */
        fun fromJson(flowModelJson: String, macroJson: String?, eventsJson: String,
                     rules: EngineRules = EngineRules(), config: GameConfig = GameConfig()): TwinEngine {
            val catalog = json.decodeFromString<ActionCatalog>(eventsJson)
            return TwinEngine(
                Simulator(json.decodeFromString<FlowModel>(flowModelJson), macroJson?.let { json.decodeFromString<MacroFile>(it) }, rules),
                catalog.actions, catalog.shocks, config,
            )
        }

        /** Used only if the catalog forgets it: carrying on is always possible. */
        val DEFAULT_NOTHING = Action(
            id = NOTHING, category = "Everyday life",
            title = mapOf(Lang.EN to "Carry on as you are", Lang.ES to "Seguir como estás"),
            description = mapOf(Lang.EN to "No new decision for two years.", Lang.ES to "Ninguna decisión nueva durante dos años."),
            repeatable = true, sources = emptyList(),
            outcome = mapOf(Lang.EN to "Two more years of the life you already have.", Lang.ES to "Dos años más de la vida que ya tienes."),
        )
    }

    private val pool: List<Action> = if (actions.any { it.id == NOTHING }) actions else listOf(DEFAULT_NOTHING) + actions
    private val byId: Map<String, Action> = pool.associateBy { it.id }

    fun action(id: String): Action? = byId[id]

    /** Life changes due at the start of the turn (they count for the actions available) and their story beats. */
    fun startTurn(s: FinancialStatement): Pair<FinancialStatement, List<StoryBeat>> {
        val next = applyDueLifeChanges(s)
        return next to beatsBetween(s, next)
    }

    /** The actions the player can take at the start of [turn] ([startTurn] applied), NOTHING first. Empty at the end. */
    fun actionsAt(s: FinancialStatement, turn: Int, used: Set<String>): List<Action> =
        if (turn >= config.maxTurns) emptyList() else actionsAt(pool, s, turn, used)

    /**
     * One decision: from [s] (the statement at the start of [turn], after [startTurn]) at node [parentId], take
     * [actionId]. Its uncertain numbers are drawn at the node's luck ([LuckMode]); a shock may happen; then
     * `monthsPerTurn` months.
     */
    fun playTurn(s: FinancialStatement, turn: Int, parentId: String, actionId: String, used: Set<String>, seed: Long,
                 mode: LuckMode, lang: Lang = Lang.EN): TurnOutcome {
        val action = actionsAt(s, turn, used).firstOrNull { it.id == actionId }
            ?: throw IllegalArgumentException("turn $turn: action $actionId is not available here")
        val nodeId = DecisionTree.childId(parentId, actionId)
        val luck = PortableRandom.nodeLuck(seed, mode, nodeId, turn)
        val outcome = resolve(action, luck)
        val beats = mutableListOf<StoryBeat>()
        if (actionId == NOTHING) {
            beats += beat(s, BeatKind.QUIET_PERIOD, NOTHING)
        } else {
            beats += beat(s, BeatKind.DECISION, actionId, "label" to action.title.getValue(lang))
            beats += beat(s, BeatKind.OUTCOME, actionId, "luck" to format2(luck),
                          *outcome.values.map { (k, v) -> k to numberText(v) }.toTypedArray())
        }
        var state = applyEffects(s, outcome.effects, actionId)
        val shock = drawShock(shocks, applyDueLifeChanges(state), turn, PortableRandom.nodeSeed(seed, mode, nodeId), config.monthsPerTurn)
        var shockValues = emptyMap<String, Double>()
        if (shock != null) {
            val sr = resolve(shock.shock, shock.luck)
            shockValues = sr.values
            beats += beat(s, BeatKind.SHOCK, shock.shock.id, "luck" to format2(shock.luck), "inMonths" to shock.month.toString(),
                          *sr.values.map { (k, v) -> k to numberText(v) }.toTypedArray())
            state = applyEffects(state, shifted(sr.effects, shock.month), shock.shock.id)
        }
        val months = advance(state, config.monthsPerTurn, beats)
        val turnRecord = Turn(index = turn, actionId = actionId, luck = luck, values = outcome.values,
                              shockId = shock?.shock?.id, shockLuck = shock?.luck, shockValues = shockValues, shockMonth = shock?.month,
                              stateAfter = months.statements.last(), beats = beats)
        return TurnOutcome(turnRecord, months.statements, months.attribution)
    }

    /** A whole life along [path] (one action id per turn; missing = NOTHING): the reference flow of the golden tests. */
    fun play(s0: FinancialStatement, path: List<String>, seed: Long, mode: LuckMode = LuckMode.SAME_LEVEL,
             lang: Lang = Lang.EN): GameRecord {
        val used = mutableSetOf<String>()
        val beats = mutableListOf(beat(s0, BeatKind.START, null, "cash" to roundToString(s0.cashChf)))
        val turns = mutableListOf<Turn>()
        val monthly = mutableListOf(s0)
        val attribution = linkedMapOf<String, Double>()
        var s = s0
        var node = DecisionTree.ROOT
        for (turn in 0 until config.maxTurns) {
            val (started, lifeBeats) = startTurn(s)
            beats += lifeBeats
            val actionId = path.getOrElse(turn) { NOTHING }
            val outcome = playTurn(started, turn, node, actionId, used, seed, mode, lang)
            used += actionId
            node = DecisionTree.childId(node, actionId)
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
    val decisions: List<String> get() = turns.map { it.actionId }
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
