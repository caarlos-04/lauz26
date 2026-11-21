package ch.lauzhack.twin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shared contract, copied from docs/README.md section 5.
// Do NOT change without telling the team. Money is CHF (Double), rates are annual fractions (0.05 = 5 %),
// `month` is the integer month index since the start of the game (0..119).

// ---------- 5.1 Enums ----------

@Serializable enum class Employment { STUDENT, EMPLOYED, PART_TIME, SELF_EMPLOYED, RETIRED, UNEMPLOYED }
@Serializable enum class Household { SINGLE, COUPLE, FAMILY, SHARED_HOUSING }
@Serializable enum class CaseType { PESSIMISTIC, NEUTRAL, OPTIMISTIC }
@Serializable enum class AssetType { CASH_LIKE, INVESTMENT_PORTFOLIO, PROPERTY, OTHER }
@Serializable enum class LiabilityType { PERSONAL_LOAN, MORTGAGE, STUDENT_LOAN, OTHER }
@Serializable enum class ExpenseCategory { RENT, LIVING, TUITION, INSURANCE, TRAVEL, OTHER }
@Serializable enum class Priority { HIGH, MEDIUM, LOW }
@Serializable enum class Lang { ES, EN }

// ---------- 5.2 Profile and state ----------

// Static profile. Produced by the interview (or loaded for Alexia).
@Serializable
data class UserProfile(
    val id: String,
    val displayName: String,
    val ageAtStart: Int,            // years
    val city: String,
    val canton: String,
    val employment: Employment,
    val household: Household,
    val startMonth: String,         // "2025-01"
    val goals: List<Goal>,
    val isSynthetic: Boolean = true,
)

// Dynamic snapshot of the person's finances at one month. This is what the engine transforms.
@Serializable
data class FinancialState(
    val month: Int,
    val cash: Double,               // CHF, may go negative (=> needs financing)
    val assets: List<Asset>,
    val liabilities: List<Liability>,
    val income: List<IncomeStream>,
    val expenses: List<ExpenseStream>,
    val employment: Employment,
) {
    val netWorth: Double
        get() = cash + assets.sumOf { it.valueChf } - liabilities.sumOf { it.principalChf }

    val monthlyNet: Double
        get() = income.sumOf { it.monthlyChf } - expenses.sumOf { it.monthlyChf } -
            liabilities.sumOf { it.monthlyPaymentChf }
}

@Serializable
data class Asset(
    val id: String,
    val type: AssetType,
    val valueChf: Double,
    val annualReturn: Double,
)

@Serializable
data class Liability(
    val id: String,
    val type: LiabilityType,
    val principalChf: Double,
    val annualRate: Double,
    val monthlyPaymentChf: Double,
)

@Serializable
data class IncomeStream(
    val id: String,
    val label: String,
    val monthlyChf: Double,
    val untilMonth: Int? = null,    // null = open-ended; stops at this month (exclusive)
)

@Serializable
data class ExpenseStream(
    val id: String,
    val category: ExpenseCategory,
    val monthlyChf: Double,
    val untilMonth: Int? = null,
)

@Serializable
data class Goal(
    val id: String,
    val name: String,
    val targetChf: Double,
    val targetMonth: Int,
    val priority: Priority,
)

// ---------- 5.3 Events ----------

@Serializable
data class Event(
    val id: String,
    val titleKey: String,
    val descriptionKey: String,
    val requires: Requirements? = null,
    val earliestTurn: Int = 0,
    val options: List<EventOption>, // ALWAYS exactly 2
)

@Serializable
data class Requirements(
    val employment: List<Employment>? = null,
    val household: List<Household>? = null,
    val minAge: Int? = null,
    val maxAge: Int? = null,
    val minCash: Double? = null,
    val requiresLiability: Boolean? = null,
)

@Serializable
data class EventOption(
    val id: String,                 // "A" | "B"
    val labelKey: String,
    val cases: Map<CaseType, CaseOutcome>,
)

@Serializable
data class CaseOutcome(
    val prob: Double,
    val effects: List<Effect>,
    val narrative: Map<Lang, String>,
)

@Serializable
sealed interface Effect {
    @Serializable @SerialName("IncomeDelta")
    data class IncomeDelta(val streamId: String, val deltaChf: Double, val months: Int?) : Effect

    @Serializable @SerialName("ExpenseDelta")
    data class ExpenseDelta(val category: ExpenseCategory, val deltaChf: Double, val months: Int?) : Effect

    @Serializable @SerialName("OneOffCost")
    data class OneOffCost(val chf: Double) : Effect

    @Serializable @SerialName("AddLiability")
    data class AddLiability(val liability: Liability) : Effect

    @Serializable @SerialName("AssetReturnDelta")
    data class AssetReturnDelta(val assetType: AssetType, val deltaAnnualRate: Double) : Effect

    @Serializable @SerialName("SetEmployment")
    data class SetEmployment(val employment: Employment) : Effect
}

// ---------- 5.4 Game and results ----------

@Serializable
data class GameSession(
    val profile: UserProfile,
    val initial: FinancialState,
    val seed: Long,
    val turns: List<Turn>,
    val config: GameConfig,
)

@Serializable
data class GameConfig(
    val horizonMonths: Int = 120,
    val monthsPerTurn: Int = 24,
    val maxTurns: Int = 5,
    val manualCaseMode: Boolean = false,
)

@Serializable
data class Turn(
    val index: Int,
    val eventId: String,
    val chosenOptionId: String,
    val resolvedCase: CaseType,
    val stateBefore: FinancialState,
    val stateAfter: FinancialState,
    val counterfactual: Map<CaseType, FinancialState>,
)

@Serializable
data class OptionPreview(
    val optionId: String,
    val perCase: Map<CaseType, CasePreview>,
)

@Serializable
data class CasePreview(
    val prob: Double,
    val stateAfter: FinancialState,
    val deltaNetWorth: Double,
    val minCash: Double,
    val narrative: String,
)

// ---------- 5.5 Monte Carlo and stats ----------

@Serializable
data class SimulationResult(
    val nPaths: Int,
    val seed: Long,
    val horizonMonths: Int,
    val cash: FanBand,
    val netWorth: FanBand,
    val probNegativeCash: Double,
    val goalProbabilities: Map<String, Double>,
    val goalMedianMonth: Map<String, Int?>,
)

@Serializable
data class FanBand(
    val p10: List<Double>,          // size = horizonMonths + 1
    val p50: List<Double>,
    val p90: List<Double>,
)

@Serializable
data class Stats(
    val finalNetWorth: Double,
    val minCash: Double,
    val monthOfMinCash: Int,
    val monthsNegativeCash: Int,
    val goalsReached: List<String>,
    val goalsMissed: List<String>,
)

@Serializable
data class PathComparison(
    val chosen: Stats,
    val alternative: Stats,
    val deltaFinalNetWorth: Double,
    val explanation: List<String>,
)

// ---------- 5.6 Interview, calibration and assumptions ----------

@Serializable
data class InterviewQuestion(
    val id: String,
    val textKey: String,
    val options: List<InterviewOption>,
)

@Serializable
data class InterviewOption(
    val id: String,
    val labelKey: String,
    val sets: Map<ProfileField, String>,
)

@Serializable
enum class ProfileField { AGE, CITY, CANTON, EMPLOYMENT, HOUSEHOLD, OPENING_CASH, MONTHLY_INCOME, MONTHLY_RENT, GOAL_TYPE, RISK }

@Serializable
data class InterviewAnswer(val questionId: String, val optionId: String)

@Serializable
data class SegmentCalibration(
    val segment: String,
    val n: Int,
    val netMonthlyMeanChf: Double,
    val netMonthlySdChf: Double,
    val netToIncomeP10: Double,
    val netToIncomeP50: Double,
    val netToIncomeP90: Double,
    val decemberUplift: Double,
    val negativeMonthRate: Double,
    val residuals: List<Double>,
)

@Serializable
data class Assumptions(
    val inflationAnnual: Double,
    val salaryGrowthAnnual: Double,
    val investmentReturn: Map<CaseType, Double>,
    val defaultCaseProbs: Map<CaseType, Double>,
    val sourceNotes: List<String>,
)

// ---------- 5.7 Chat ----------

@Serializable enum class ChatRole { USER, FUTURE_SELF }

@Serializable
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val offline: Boolean = false,   // UI only: answer came from the canned fallback, not the LLM
)

data class ChatContext(
    val session: GameSession,
    val stats: Stats,
    val comparison: PathComparison?,
    val simulation: SimulationResult?,
    val assumptions: Assumptions,
    val language: Lang,
    val editedTurns: Set<Int> = emptySet(),  // UI only: turn indices changed in the what-if tree
)
