package com.lauzhack.twin.ai

import com.lauzhack.twin.ui.originOf
import com.lauzhack.twin.ui.Origin
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.LocalizedText
import com.lauzhack.twin.model.ParallelKind
import com.lauzhack.twin.model.StoryBeat
import com.lauzhack.twin.model.Turn
import com.lauzhack.twin.model.city
import com.lauzhack.twin.ui.cash
import com.lauzhack.twin.ui.debt
import com.lauzhack.twin.ui.monthlyNet
import com.lauzhack.twin.ui.netWorth
import com.lauzhack.twin.ui.progress
import com.lauzhack.twin.ui.targetMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a [ChatContext] (the branch selected in the tree, as a [GameResult]) into the [LifeBriefing] the agent
 * reads. Only copies numbers the engine already computed and resolves every label in the chat language.
 *
 * @param eventById catalogue lookup for the event of each turn.
 * @param text resolves UI translation keys (goal names) in the current language.
 */
class BriefingMapper(
    private val eventById: (String) -> Event?,
    private val text: suspend (String) -> String,
) {
    suspend fun map(ctx: ChatContext): LifeBriefing {
        val r = ctx.result
        val session = r.session
        val person = session.initial.person
        val lang = ctx.language
        val end = session.turns.lastOrNull()?.stateAfter ?: session.initial
        val goalNames = (session.initial.goals + end.goals).associate { it.name to text(it.name) }

        return LifeBriefing(
            language = if (lang == Lang.ES) ChatLanguage.ES else ChatLanguage.EN,
            profile = ProfileLine(
                name = person.displayName,
                ageAtStart = person.age,
                startYear = session.initial.period.take(4).toIntOrNull() ?: 2025,
                horizonYears = session.config.horizonMonths / 12,
                city = person.city.orEmpty(),
                household = person.household.readable(),
                objective = session.preferences.objective.readable(),
                minCashChf = session.preferences.minCashChf,
            ),
            start = session.initial.snapshot(),
            end = end.snapshot(),
            lifeAtStart = life(session.initial, ctx.facts, lang),
            lifeAtEnd = life(end, ctx.facts, lang),
            turns = turns(session.turns, session.initial, lang, ctx.whatIfTurns),
            moments = r.beats.mapNotNull { moment(it, ctx.facts, lang, goalNames, session.isOver) },
            goals = end.goals.map { g ->
                GoalLine(goalNames[g.name] ?: text(g.name), g.targetChf, g.targetMonth(session.initial), g.name in r.stats.goalsReached, (g.progress(end) * 100).roundToInt())
            },
            risk = RiskLine(r.stats.minCash, r.stats.monthOfMinCash, r.stats.monthsNegativeCash, r.stats.monthsBelowMinCash, r.stats.financingCostChf),
            outlook = ctx.noiseBand?.takeIf { it.p50.isNotEmpty() }?.let { OutlookLine(it.p10.last(), it.p50.last(), it.p90.last()) },
            drivers = r.attribution.items.sortedByDescending { abs(it.chf) }.take(6).map { item ->
                DriverLine(driverLabel(item.key), item.chf, originLabel(originOf(item.key)))
            },
            parallelLives = r.parallelLives.map { life ->
                ParallelLine(
                    title = when (life.kind) {
                        ParallelKind.OTHER_FIRST_CHOICE -> "Other first choice"
                        ParallelKind.OPTIMAL_STRATEGY -> "Turn-by-turn strategy (the best expected result at each turn; a heuristic, not an exact optimum)"
                    },
                    choices = life.turns.mapNotNull { t -> choiceLine(t, lang) },
                    finalNetWorthChf = life.stats.finalNetWorth,
                    deltaVsThisPathChf = r.stats.finalNetWorth - life.stats.finalNetWorth,
                    goalsReached = life.stats.goalsReached.size,
                    monthsNegativeCash = life.stats.monthsNegativeCash,
                )
            },
            assumptionNotes = ctx.assumptions.sourceNotes,
            isWhatIf = ctx.whatIfTurns.isNotEmpty(),
            complete = session.isOver,
            decisionSources = session.turns.mapNotNull { it.eventId?.let(eventById) }.distinctBy { it.id }
                .filter { it.sources.isNotEmpty() }
                .map { SourceLine(it.title.of(lang), it.sources) },
        )
    }

    private fun turns(turns: List<Turn>, initial: FinancialStatement, lang: Lang, edited: Set<Int>): List<TurnLine> =
        turns.mapIndexed { i, turn ->
            val before = if (i == 0) initial else turns[i - 1].stateAfter
            val event = turn.eventId?.let(eventById)
            val chosen = event?.options?.firstOrNull { it.id == turn.chosenOptionId }
            val other = event?.options?.firstOrNull { it.id != turn.chosenOptionId }
            TurnLine(
                turn = i + 1,
                month = before.month,
                quiet = event == null,
                question = event?.title?.of(lang),
                chosen = chosen?.label?.of(lang),
                notChosen = other?.label?.of(lang),
                luck = turn.resolvedCase?.toLuck(),
                outcome = turn.resolvedCase?.let { chosen?.cases?.get(it)?.narrative?.of(lang) },
                netWorthBeforeChf = before.netWorth,
                netWorthAfterChf = turn.stateAfter.netWorth,
                cashAfterChf = turn.stateAfter.cash,
                editedInWhatIf = i in edited,
            )
        }

    private fun choiceLine(t: Turn, lang: Lang): String? {
        val event = t.eventId?.let(eventById) ?: return null
        val option = event.options.firstOrNull { it.id == t.chosenOptionId } ?: return null
        return "${event.title.of(lang)}: ${option.label.of(lang)}"
    }

    /** Short English line for a moment; the decision beats are already in TURNS. */
    private fun moment(beat: StoryBeat, facts: FactCatalog, lang: Lang, goalNames: Map<String, String>, complete: Boolean): MomentLine? {
        val d = beat.data
        val text = when (beat.kind) {
            BeatKind.LIFE_CHANGE -> when {
                d["flag"] != null -> "Milestone: ${facts.flags[d["flag"]]?.of(lang) ?: d["flag"]}"
                d["fact"] != null -> {
                    val key = FactKey.entries.firstOrNull { it.name == d["fact"] }
                    val label = key?.let { facts.facts[it]?.get(d["value"])?.of(lang) } ?: d["value"]
                    "Life change (${d["fact"]!!.lowercase()}): $label"
                }
                d["employment"] != null -> "Work changed to: ${d["employment"]!!.lowercase().replace('_', ' ')}"
                else -> null
            }
            BeatKind.CASH_NEGATIVE -> "Cash went below zero (financing needed)"
            BeatKind.CASH_RECOVERED -> "Cash was positive again"
            BeatKind.LOW_POINT -> if (complete) "Lowest cash of the ten years" else "Lowest cash so far"
            BeatKind.GOAL_REACHED -> "Goal reached: ${goalNames[d["goal"]] ?: d["goal"]}"
            BeatKind.GOAL_MISSED -> "Goal missed: ${goalNames[d["goal"]] ?: d["goal"]}"
            BeatKind.QUIET_PERIOD -> null
            BeatKind.START, BeatKind.DECISION, BeatKind.CASE_RESOLVED, BeatKind.END -> null
        } ?: return null
        return MomentLine(beat.month, text)
    }

    private fun life(s: FinancialStatement, facts: FactCatalog, lang: Lang): List<String> =
        s.person.facts.mapNotNull { (key, code) -> facts.facts[key]?.get(code)?.of(lang) } +
            s.person.flags.map { facts.flags[it]?.of(lang) ?: it }

    /** Attribution keys of docs/README.md 5.3 ("income:salary", "expense:rent", "loan_interest"...). */
    private fun driverLabel(key: String): String = when {
        key.startsWith("income:") -> "Income (${key.removePrefix("income:")})"
        key.startsWith("expense:") -> "Spending (${key.removePrefix("expense:").lowercase().replace('_', ' ')})"
        key == "loan_interest" -> "Loan interest"
        key == "financing_cost" -> "Cost of negative cash"
        key == "investment_growth" -> "Investment growth"
        else -> "Other"
    }

    private fun originLabel(o: Origin) = when (o) {
        Origin.DATASET -> "learned from the synthetic dataset (flow model)"
        Origin.PUBLIC -> "public Swiss data"
        Origin.EVENT -> "set by a decision (its case); salary also grows with public wage indices"
        Origin.ASSUMPTION -> "an assumption (see ASSUMPTIONS AND LIMITS)"
    }

    private fun FinancialStatement.snapshot() = MoneySnapshot(
        month = month,
        cashChf = cash,
        netWorthChf = netWorth,
        monthlyNetChf = monthlyNet,
        debtChf = debt,
        employment = person.employment.readable(),
    )

    private fun CaseType.toLuck() = when (this) {
        CaseType.PESSIMISTIC -> Luck.BAD
        CaseType.NEUTRAL -> Luck.AVERAGE
        CaseType.OPTIMISTIC -> Luck.GOOD
    }

    private fun Enum<*>.readable() = name.lowercase().replace('_', ' ')
}

/** Text in [lang], falling back to any language present. */
fun LocalizedText.of(lang: Lang): String = this[lang] ?: values.firstOrNull().orEmpty()
