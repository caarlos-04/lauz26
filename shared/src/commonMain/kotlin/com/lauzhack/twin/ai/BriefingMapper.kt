package com.lauzhack.twin.ai

import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.GoalState
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.LocalizedText
import com.lauzhack.twin.model.ParallelKind
import com.lauzhack.twin.model.StoryBeat
import com.lauzhack.twin.model.Turn
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
        val profile = session.profile
        val lang = ctx.language
        val end = session.turns.lastOrNull()?.stateAfter ?: session.initial
        val goalNames = profile.goals.associate { it.id to text(it.name) }

        return LifeBriefing(
            language = if (lang == Lang.ES) ChatLanguage.ES else ChatLanguage.EN,
            profile = ProfileLine(
                name = profile.displayName,
                ageAtStart = profile.ageAtStart,
                startYear = profile.startMonth.take(4).toIntOrNull() ?: 2025,
                horizonYears = session.config.horizonMonths / 12,
                city = profile.city,
                household = profile.household.readable(),
                objective = session.preferences.objective.readable(),
                minCashChf = session.preferences.minCashChf,
            ),
            start = session.initial.snapshot(),
            end = end.snapshot(),
            lifeAtStart = life(session.initial, ctx.facts, lang),
            lifeAtEnd = life(end, ctx.facts, lang),
            turns = turns(session.turns, session.initial, lang, ctx.whatIfTurns),
            moments = r.beats.mapNotNull { moment(it, ctx.facts, lang, goalNames) },
            goals = end.goals.map { g ->
                GoalLine(goalNames[g.id] ?: text(g.name), g.targetChf, g.targetMonth, g.status == GoalState.REACHED, (g.progressRatio * 100).roundToInt())
            },
            risk = RiskLine(r.stats.minCash, r.stats.monthOfMinCash, r.stats.monthsNegativeCash, r.stats.monthsBelowMinCash, r.stats.financingCostChf),
            outlook = r.cashBand.takeIf { it.p50.isNotEmpty() }?.let { OutlookLine(it.p10.last(), it.p50.last(), it.p90.last()) },
            drivers = r.attribution.items.sortedByDescending { abs(it.chf) }.take(6).map { item ->
                DriverLine(driverLabel(item.key, item.sourceEventId, lang), item.chf)
            },
            parallelLives = r.parallelLives.map { life ->
                ParallelLine(
                    title = when (life.kind) {
                        ParallelKind.OTHER_FIRST_CHOICE -> "Other first choice"
                        ParallelKind.OPTIMAL_STRATEGY -> "Best strategy (computed)"
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
                netWorthBeforeChf = before.balanceSheet.netWorthChf,
                netWorthAfterChf = turn.stateAfter.balanceSheet.netWorthChf,
                cashAfterChf = turn.stateAfter.balanceSheet.cashChf,
                editedInWhatIf = i in edited,
            )
        }

    private fun choiceLine(t: Turn, lang: Lang): String? {
        val event = t.eventId?.let(eventById) ?: return null
        val option = event.options.firstOrNull { it.id == t.chosenOptionId } ?: return null
        return "${event.title.of(lang)}: ${option.label.of(lang)}"
    }

    /** Short English line for a moment; the decision beats are already in TURNS. */
    private fun moment(beat: StoryBeat, facts: FactCatalog, lang: Lang, goalNames: Map<String, String>): MomentLine? {
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
            BeatKind.LOW_POINT -> "Lowest cash of the ten years"
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

    private fun driverLabel(key: String, sourceEventId: String?, lang: Lang): String {
        val base = when {
            key.startsWith("income:") -> "Income (${key.removePrefix("income:")})"
            key.startsWith("expense:") -> "Spending (${key.removePrefix("expense:").lowercase()})"
            key.startsWith("event:") -> "One-off costs and loans"
            key == "debt_interest" -> "Loan interest"
            key == "financing_cost" -> "Cost of negative cash"
            key == "investment_return" -> "Investment returns"
            else -> "Other"
        }
        val event = sourceEventId?.let(eventById)?.title?.of(lang)
        return if (event != null) "$base, from \"$event\"" else base
    }

    private fun FinancialStatement.snapshot() = MoneySnapshot(
        month = month,
        cashChf = balanceSheet.cashChf,
        netWorthChf = balanceSheet.netWorthChf,
        monthlyNetChf = metrics.monthlyNetCashFlowChf,
        debtChf = balanceSheet.totalLiabilitiesChf,
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
