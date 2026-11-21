package ch.lauzhack.twin.ai

import kotlinx.serialization.Serializable

/**
 * Everything the future self is allowed to know: one life path (the one selected in the decision tree),
 * already computed by the engine. It is the dynamic part of the prompt; the LLM never computes on it.
 *
 * Owned by this module on purpose, so `ai/` does not depend on the UI model. Money is CHF,
 * probabilities are 0.0..1.0, months are indices since the start of the game.
 */
@Serializable
data class LifeBriefing(
    val language: ChatLanguage,
    val profile: ProfileLine,
    val start: MoneySnapshot,
    val finalNetWorthChf: Double,
    val decisions: List<DecisionLine>,
    val goals: List<GoalLine>,
    val risk: RiskLine,
    val outlook: OutlookLine? = null,
    val otherPath: OtherPathLine? = null,
    val assumptionNotes: List<String> = emptyList(),
    /** True when the user changed decisions or luck in the tree: this is not the life they played. */
    val isWhatIf: Boolean = false,
)

@Serializable enum class ChatLanguage { EN, ES }

/** Luck drawn for a decision (pessimistic / neutral / optimistic case). */
@Serializable enum class Luck { BAD, AVERAGE, GOOD }

@Serializable
data class ProfileLine(
    val name: String,
    val ageAtStart: Int,
    val startYear: Int,
    val horizonYears: Int,
    val city: String,
    val employmentAtStart: String,
    val employmentAfterLastDecision: String,
    val household: String,
)

@Serializable
data class MoneySnapshot(
    val month: Int,
    val cashChf: Double,
    val netWorthChf: Double,
    val monthlyNetChf: Double,
    val debtChf: Double,
)

@Serializable
data class DecisionLine(
    val turn: Int,              // 1-based
    val month: Int,             // month the decision was taken
    val question: String,
    val chosen: String,
    val notChosen: String,
    val luck: Luck,
    val outcome: String,        // narrative of the drawn case
    val netWorthBeforeChf: Double,
    val netWorthAfterChf: Double,
    val cashAfterChf: Double,
    /** Changed by the user in the what-if tree (option or luck differs from the played game). */
    val editedInWhatIf: Boolean = false,
)

@Serializable
data class GoalLine(
    val name: String,
    val targetChf: Double,
    val targetMonth: Int,
    val reached: Boolean,
    /** Share of simulated futures where the goal is reached, if a simulation exists. */
    val probability: Double? = null,
)

@Serializable
data class RiskLine(
    val minCashChf: Double,
    val minCashMonth: Int,
    val monthsNegativeCash: Int,
)

/** Monte Carlo summary of the final net worth for this path. */
@Serializable
data class OutlookLine(
    val paths: Int,
    val finalNetWorthP10Chf: Double,
    val finalNetWorthP50Chf: Double,
    val finalNetWorthP90Chf: Double,
    val finalCashP50Chf: Double,
    val probNegativeCash: Double,
)

/** The life with the opposite choices, as computed by the engine. */
@Serializable
data class OtherPathLine(
    val finalNetWorthChf: Double,
    val deltaVsThisPathChf: Double,   // this path minus the other one
    val goalsReached: Int,
    val monthsNegativeCash: Int,
    /** Decisions that weighed most in the difference, most important first. */
    val mainDrivers: List<String>,
)
