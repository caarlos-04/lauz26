package ch.lauzhack.twin.ai

import ch.lauzhack.twin.model.CaseType
import ch.lauzhack.twin.model.ChatContext
import ch.lauzhack.twin.model.Event
import ch.lauzhack.twin.model.FinancialState
import ch.lauzhack.twin.model.Lang

/**
 * Turns the UI's [ChatContext] (selected branch of the tree + engine results) into the
 * [LifeBriefing] the agent reads. Only copies numbers the engine already computed.
 *
 * @param eventById catalog lookup for the event of each turn.
 * @param text resolves translation keys (event titles, option labels, goal names) in the current language.
 */
class BriefingMapper(
    private val eventById: (String) -> Event?,
    private val text: suspend (String) -> String,
) {
    suspend fun map(ctx: ChatContext): LifeBriefing {
        val session = ctx.session
        val profile = session.profile
        val lang = ctx.language
        val turns = session.turns

        val decisions = turns.mapIndexed { i, turn ->
            val event = eventById(turn.eventId)
            val chosen = event?.options?.firstOrNull { it.id == turn.chosenOptionId }
            val other = event?.options?.firstOrNull { it.id != turn.chosenOptionId }
            DecisionLine(
                turn = i + 1,
                month = turn.stateBefore.month,
                question = event?.let { text(it.titleKey) } ?: turn.eventId,
                chosen = chosen?.let { text(it.labelKey) } ?: turn.chosenOptionId,
                notChosen = other?.let { text(it.labelKey) } ?: "-",
                luck = turn.resolvedCase.toLuck(),
                outcome = chosen?.cases?.get(turn.resolvedCase)?.narrative?.let { it[lang] ?: it.values.firstOrNull() }.orEmpty(),
                netWorthBeforeChf = turn.stateBefore.netWorth,
                netWorthAfterChf = turn.stateAfter.netWorth,
                cashAfterChf = turn.stateAfter.cash,
                editedInWhatIf = i in ctx.editedTurns,
            )
        }

        val goals = profile.goals.map { g ->
            GoalLine(
                name = text(g.name),
                targetChf = g.targetChf,
                targetMonth = g.targetMonth,
                reached = g.id in ctx.stats.goalsReached,
                probability = ctx.simulation?.goalProbabilities?.get(g.id),
            )
        }

        val outlook = ctx.simulation?.let { sim ->
            OutlookLine(
                paths = sim.nPaths,
                finalNetWorthP10Chf = sim.netWorth.p10.last(),
                finalNetWorthP50Chf = sim.netWorth.p50.last(),
                finalNetWorthP90Chf = sim.netWorth.p90.last(),
                finalCashP50Chf = sim.cash.p50.last(),
                probNegativeCash = sim.probNegativeCash,
            )
        }

        val otherPath = ctx.comparison?.let { c ->
            OtherPathLine(
                finalNetWorthChf = c.alternative.finalNetWorth,
                deltaVsThisPathChf = c.deltaFinalNetWorth,
                goalsReached = c.alternative.goalsReached.size,
                monthsNegativeCash = c.alternative.monthsNegativeCash,
                // explanation = event ids ordered by impact
                mainDrivers = c.explanation.take(3).map { id -> eventById(id)?.let { text(it.titleKey) } ?: id },
            )
        }

        return LifeBriefing(
            language = if (lang == Lang.ES) ChatLanguage.ES else ChatLanguage.EN,
            profile = ProfileLine(
                name = profile.displayName,
                ageAtStart = profile.ageAtStart,
                startYear = profile.startMonth.take(4).toIntOrNull() ?: 2025,
                horizonYears = session.config.horizonMonths / 12,
                city = profile.city,
                employmentAtStart = session.initial.employment.readable(),
                employmentAfterLastDecision = (turns.lastOrNull()?.stateAfter ?: session.initial).employment.readable(),
                household = profile.household.readable(),
            ),
            start = session.initial.snapshot(),
            finalNetWorthChf = ctx.stats.finalNetWorth,
            decisions = decisions,
            goals = goals,
            risk = RiskLine(ctx.stats.minCash, ctx.stats.monthOfMinCash, ctx.stats.monthsNegativeCash),
            outlook = outlook,
            otherPath = otherPath,
            assumptionNotes = ctx.assumptions.sourceNotes,
            isWhatIf = ctx.editedTurns.isNotEmpty(),
        )
    }

    private fun FinancialState.snapshot() = MoneySnapshot(
        month = month,
        cashChf = cash,
        netWorthChf = netWorth,
        monthlyNetChf = monthlyNet,
        debtChf = liabilities.sumOf { it.principalChf },
    )

    private fun CaseType.toLuck() = when (this) {
        CaseType.PESSIMISTIC -> Luck.BAD
        CaseType.NEUTRAL -> Luck.AVERAGE
        CaseType.OPTIMISTIC -> Luck.GOOD
    }

    private fun Enum<*>.readable() = name.lowercase().replace('_', ' ')
}
