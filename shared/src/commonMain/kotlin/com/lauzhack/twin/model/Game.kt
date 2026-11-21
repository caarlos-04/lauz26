package com.lauzhack.twin.model

import kotlinx.serialization.Serializable

@Serializable
data class GameSession(
    val initial: FinancialStatement,        // month 0 (from the interview, or a dataset profile such as Alexia)
    val seed: Long,
    val preferences: Preferences,
    val turns: List<Turn>,
    val config: GameConfig = GameConfig(),
)

@Serializable
data class GameConfig(
    val horizonMonths: Int = 120,
    val monthsPerTurn: Int = 24,
    val maxTurns: Int = 5,
    val manualCaseMode: Boolean = false,
)

@Serializable
data class Preferences(
    val objective: Objective,
    val minCashChf: Double,
    val penaltyChfPerMonthBelowMin: Double,
)

@Serializable
data class Turn(
    val index: Int,
    val eventId: String?,                   // null = quiet period
    val chosenOptionId: String?,
    val luck: Double,                       // 0..1
    val resolvedCase: CaseType?,
    val stateAfter: FinancialStatement,
    val counterfactual: Map<CaseType, FinancialStatement>,  // the option NOT chosen
    val beats: List<StoryBeat>,
)

// Shown before choosing.
@Serializable
data class OptionPreview(
    val optionId: String,
    val perCase: Map<CaseType, CasePreview>,
    val outlook: Outlook? = null,
)

@Serializable
data class CasePreview(
    val prob: Double,
    val stateAfter: FinancialStatement,
    val deltaNetWorth: Double,
    val minCash: Double,
    val pendingChanges: List<PendingChange>,
    val narrative: LocalizedText,
)
