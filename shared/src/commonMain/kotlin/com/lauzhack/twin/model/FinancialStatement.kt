package com.lauzhack.twin.model

import kotlinx.serialization.Serializable

// Static profile. Produced by the interview (or loaded for Alexia).
@Serializable
data class UserProfile(
    val id: String,
    val displayName: String,
    val ageAtStart: Int,
    val city: String,
    val employment: Employment,
    val household: Household,
    val startMonth: String,                 // "2025-01"
    val goals: List<Goal>,
)

@Serializable
data class Goal(
    val id: String,
    val name: String,
    val targetChf: Double,
    val targetDate: String,                 // "2029-12-31"
    val targetMonth: Int,                   // statement month at the end of the target month (2029-12-31 -> 60)
    val priority: Priority,
)

// Everything we know about the person at one point in time (docs/financial-statement.md).
// Month 0 = opening statement; month t = end of month t, with that month's cash flow.
@Serializable
data class FinancialStatement(
    val version: Int,
    val profileId: String,
    val month: Int,
    val period: String,                     // "2025-01-01" (opening) or "2025-01"
    val origin: Origin,                     // OBSERVED (dataset months) or SIMULATED
    val currency: String,                   // "CHF"
    val person: PersonSnapshot,
    val accounts: List<CashAccount>,        // cash lives only here
    val investments: List<Investment>,
    val liabilities: List<Liability>,
    val incomeStreams: List<IncomeStream>,
    val expenseStreams: List<ExpenseStream>,
    val cashFlow: CashFlow?,                // null at month 0
    val balanceSheet: BalanceSheet,
    val goals: List<GoalStatus>,
    val scenarioParameters: List<ScenarioParameter>,
    val metrics: StatementMetrics,
    val pendingLifeChanges: List<LifeChange>,
)

@Serializable
data class PersonSnapshot(
    val displayName: String,
    val ageAtStart: Int,
    val age: Int,
    val city: String,
    val canton: String?,                    // null if the person moved city
    val country: String,
    val employment: Employment,
    val household: Household,
    val facts: Map<FactKey, String>,        // codes from content/facts.json
    val flags: List<String>,                // milestones
    val synthetic: Boolean,
)

@Serializable
data class CashAccount(
    val id: String,
    val type: AccountType,
    val currency: String,
    val balanceChf: Double,
    val status: AccountStatus,
)

@Serializable
data class Investment(
    val id: String,
    val accountId: String?,
    val type: AssetType,
    val valueChf: Double,
    val annualReturn: Double?,              // null in the dataset: filled from Assumptions
    val valuationBasis: String,
)

@Serializable
data class Liability(
    val id: String,
    val type: LiabilityType,
    val principalChf: Double,
    val annualRate: Double,
    val monthlyPaymentChf: Double,
    val paymentIsAssumed: Boolean,
    val datasetMonthlyPaymentChf: Double? = null,
    val sourceEventId: String? = null,
)

@Serializable
data class IncomeStream(
    val id: String,
    val label: String,
    val monthlyChf: Double,
    val fromMonth: Int = 0,                 // active when fromMonth <= t
    val untilMonth: Int? = null,            // ... and t < untilMonth (null = open-ended)
    val indexation: Indexation,
    val origin: Origin,
    val sourceEventId: String? = null,
)

@Serializable
data class ExpenseStream(
    val id: String,
    val category: ExpenseCategory,
    val monthlyChf: Double,
    val fromMonth: Int = 0,
    val untilMonth: Int? = null,
    val indexation: Indexation,
    val origin: Origin,
    val sourceEventId: String? = null,
)

@Serializable
data class CashFlow(
    val openingCashChf: Double,
    val income: List<FlowLine>,
    val expenses: List<FlowLine>,
    val debtService: List<DebtLine>,
    val otherChf: Double,                   // not itemised (observed months: noise, December bonus)
    val financingCostChf: Double,           // interest on negative cash
    val totalIncomeChf: Double,
    val totalExpensesChf: Double,
    val totalDebtServiceChf: Double,
    val netCashFlowChf: Double,
    val closingCashChf: Double,
)

@Serializable
data class FlowLine(
    val key: String,                        // "income:income", "expense:RENT"
    val amountChf: Double,
    val origin: Origin,
    val sourceEventId: String?,
)

@Serializable
data class DebtLine(
    val liabilityId: String,
    val paymentChf: Double,
    val interestChf: Double,
    val principalRepaidChf: Double,
    val sourceEventId: String?,
)

@Serializable
data class BalanceSheet(
    val cashChf: Double,
    val investmentsChf: Double,
    val totalAssetsChf: Double,
    val totalLiabilitiesChf: Double,
    val netWorthChf: Double,
)

@Serializable
data class GoalStatus(
    val id: String,
    val name: String,
    val targetChf: Double,
    val targetDate: String,
    val targetMonth: Int,
    val priority: Priority,
    val progressBasis: ProgressBasis,
    val currentChf: Double,
    val progressRatio: Double,
    val monthsLeft: Int,
    val status: GoalState,
)

@Serializable
data class ScenarioParameter(
    val id: String,
    val scenarioId: String,
    val parameter: String,                  // "annual_tuition_chf", "study_duration_years"
    val value: Double,
    val unit: String,
    val frequency: String,
    val sourceType: String,
    val notes: String,
)

@Serializable
data class StatementMetrics(
    val basis: String,                      // "RUN_RATE"
    val monthlyIncomeChf: Double,
    val monthlyOutflowsChf: Double,
    val monthlyNetCashFlowChf: Double,
    val savingsRate: Double?,
    val debtServiceRatio: Double?,
    val rentToIncomeRatio: Double?,
    val emergencyFundMonths: Double?,
    val liquidityStatus: LiquidityStatus,
    val netWorthChf: Double,
)

// A life change decided by an event, applied when its month arrives. Exactly one of employment / fact / flag is set.
@Serializable
data class LifeChange(
    val month: Int,
    val sourceEventId: String,
    val employment: Employment? = null,
    val factKey: FactKey? = null,
    val factValue: String? = null,
    val flag: String? = null,
)
