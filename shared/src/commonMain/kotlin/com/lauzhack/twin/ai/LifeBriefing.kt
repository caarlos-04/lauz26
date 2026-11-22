package com.lauzhack.twin.ai

import kotlinx.serialization.Serializable

/**
 * Everything the future self is allowed to know: one life path (the branch selected in the decision tree),
 * already computed by the engine and with every label resolved. It is the dynamic part of the prompt;
 * the LLM never computes on it. Built by [BriefingMapper] from [ChatContext].
 * Money is CHF, months are indices since the start of the game.
 */
@Serializable
data class LifeBriefing(
    val language: ChatLanguage,
    val profile: ProfileLine,
    val start: MoneySnapshot,
    val end: MoneySnapshot,
    val lifeAtStart: List<String>,
    val lifeAtEnd: List<String>,
    val turns: List<TurnLine>,
    val moments: List<MomentLine>,
    val goals: List<GoalLine>,
    val risk: RiskLine,
    val outlook: OutlookLine? = null,
    val drivers: List<DriverLine> = emptyList(),
    val assumptionNotes: List<String> = emptyList(),
    /** True when the user changed decisions or luck in the tree: this is not the life they played. */
    val isWhatIf: Boolean = false,
    /**
     * False for a moment in the middle of a life (a node of the tree before the end): "now" is [end] and the years
     * after it have not happened.
     */
    val complete: Boolean = true,
    /** Sources of the numbers of the decisions taken (Action.sources) and of the shocks, one entry each. */
    val decisionSources: List<SourceLine> = emptyList(),
)

/** The sources of one decision's numbers, as written in the event catalogue. */
@Serializable
data class SourceLine(val question: String, val sources: List<String>)

@Serializable enum class ChatLanguage { EN, ES }

/** Luck of a decision: its percentile told in words (bad below p35, good above p65). */
@Serializable enum class Luck { BAD, AVERAGE, GOOD }

@Serializable
data class ProfileLine(
    val name: String,
    val ageAtStart: Int,
    val startYear: Int,
    val horizonYears: Int,
    val city: String,
    val household: String,
    val objective: String,
    val minCashChf: Double,
)

@Serializable
data class MoneySnapshot(
    val month: Int,
    val cashChf: Double,
    val netWorthChf: Double,
    val monthlyNetChf: Double,
    val debtChf: Double,
    val employment: String,
)

/** One turn: a decision, or a quiet period when no event fitted ([quiet] = true, decision fields null). */
@Serializable
data class TurnLine(
    val turn: Int,              // 1-based
    val month: Int,             // month the turn started
    val quiet: Boolean,
    val decision: String? = null,   // the action taken (its title)
    val shock: String? = null,      // something that happened without being chosen (job loss), with its outcome
    val luck: Luck? = null,
    val luckPercentile: Int? = null,   // the percentile the decision's uncertain numbers were drawn at
    val outcome: String? = null,
    val netWorthBeforeChf: Double,
    val netWorthAfterChf: Double,
    val cashAfterChf: Double,
    /** Different from the life first played (another decision, or another luck). */
    val editedInWhatIf: Boolean = false,
)

/** A story moment (engine StoryBeat) already turned into a short English line. */
@Serializable
data class MomentLine(val month: Int, val text: String)

@Serializable
data class GoalLine(
    val name: String,
    val targetChf: Double,
    val targetMonth: Int,
    val reached: Boolean,
    val progressPercent: Int,
)

@Serializable
data class RiskLine(
    val minCashChf: Double,
    val minCashMonth: Int,
    val monthsNegativeCash: Int,
    val monthsBelowMinCash: Int,
    val financingCostChf: Double,
)

/** Monte Carlo cash band at the end of the horizon. */
@Serializable
data class OutlookLine(
    val finalCashP10Chf: Double,
    val finalCashP50Chf: Double,
    val finalCashP90Chf: Double,
)

/** One line of the net worth attribution (start + sum of drivers = end). */
@Serializable
data class DriverLine(
    val label: String,
    val chf: Double,
    /** Where this kind of figure comes from: "dataset", "decision", "assumption" (ui/Trace.kt originOf). */
    val origin: String = "",
)

