package com.lauzhack.twin.model

import kotlinx.serialization.Serializable

// A story moment. Structured data only: the UI renders it and the LLM narrates it.
@Serializable
data class StoryBeat(
    val month: Int,
    val age: Int,
    val kind: BeatKind,
    val eventId: String? = null,
    val data: Map<String, String> = emptyMap(),
)

// start + sum(items) == end
@Serializable
data class Attribution(
    val startNetWorth: Double,
    val items: List<AttributionItem>,
    val endNetWorth: Double,
)

// What each decision contributes comes from the parallel lives (same luck, the other choice), not from tags.
@Serializable
data class AttributionItem(
    val key: String,                        // "income:salary", "expense:rent", "expense:groceries", "loan_interest",
    val chf: Double,                        // "financing_cost", "investment_growth"
)

@Serializable
data class Stats(
    val finalNetWorth: Double,
    val minCash: Double,
    val monthOfMinCash: Int,
    val monthsNegativeCash: Int,
    val monthsBelowMinCash: Int,
    val financingCostChf: Double,
    val goalsReached: List<String>,
    val goalsMissed: List<String>,
)

@Serializable
data class ParallelLife(
    val kind: ParallelKind,
    val turns: List<Turn>,
    val final: FinancialStatement,
    val stats: Stats,
    val attribution: Attribution,
)

// Look-ahead for one option with the decision tree.
@Serializable
data class Outlook(
    val expectedFinalNetWorth: Double,
    val worstFinalNetWorth: Double,
    val bestFinalNetWorth: Double,
    val probNegativeCash: Double,
    val expectedUtility: Double,
)

@Serializable
data class SensitivityItem(
    val parameter: String,
    val lowValue: Double,
    val highValue: Double,
    val utilityGapAtLow: Double,            // utility(A) - utility(B)
    val utilityGapAtHigh: Double,
    val breakEven: Double?,
)

@Serializable
data class GameResult(
    val session: GameSession,
    val stats: Stats,
    val attribution: Attribution,
    val beats: List<StoryBeat>,
    val parallelLives: List<ParallelLife>,
    val sensitivity: List<SensitivityItem>,
)
