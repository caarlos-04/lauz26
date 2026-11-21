package com.lauzhack.twin.model

import kotlinx.serialization.Serializable

// The person at one point in time (docs/financial-statement.md). Only what the datasets have and what the simulator
// needs to produce the next statement. Month 0 = opening; month t = end of month t, with that month's cash flow.
// Anything else (net worth, goal progress, ratios) is computed from these fields when needed.
@Serializable
data class FinancialStatement(
    val profileId: String,                  // profile_id, or "USER-..." for a person from the interview
    val month: Int,                         // 0..120
    val period: String,                     // "2025-01-01" (opening) or "2025-01" (end of that month)
    val person: Person,
    val cashChf: Double,                    // accounts.opening_balance_chf at month 0, then simulated
    val investmentsChf: Double,             // assets, the investment_portfolio row (0 if none)
    val loan: Loan?,                        // liabilities: 0 or 1 personal loan
    val monthlyIncomeChf: Double,           // profiles.reference_monthly_net_income_chf, then trends and events
    val monthlyRentChf: Double,             // profiles.reference_monthly_rent_chf, then trends and events
    val eventExpensesChf: Map<ExpenseCategory, Double>,  // recurring monthly expenses added by events (e.g. TUITION)
    val cashFlow: CashFlow?,                // what happened during this month; null at month 0
    val goals: List<Goal>,                  // goals
    val pendingChanges: List<PendingChange>,// decided by events, applied when their month arrives
)

@Serializable
data class Person(
    val displayName: String,
    val age: Int,
    val employment: Employment,             // work only; studying is facts[EDUCATION]
    val household: Household,
    val facts: Map<FactKey, String>,        // EDUCATION, OCCUPATION, CITY, HOUSING, FAMILY: codes of content/facts.json
    val flags: List<String>,                // milestones
)

val Person.city: String? get() = facts[FactKey.CITY]
val Person.isStudying: Boolean get() = facts[FactKey.EDUCATION]?.endsWith("_IN_PROGRESS") == true

@Serializable
data class Loan(
    val principalChf: Double,
    val annualRate: Double,
    val monthlyPaymentChf: Double,          // the dataset has 0 (never paid): we assume a 60-month annuity
)

// The month's cash flow, by category. net = sum(income) - sum(expenses) - loanPayment - financingCost;
// cash(t) = cash(t-1) + net.
@Serializable
data class CashFlow(
    val incomeChf: Map<String, Double>,     // "salary", "bonus", "loan" (a new loan paid out)
    val expensesChf: Map<String, Double>,   // "rent", learned flows ("groceries", "travel"...), event expenses ("TUITION")
    val loanPaymentChf: Double,
    val financingCostChf: Double,           // interest on negative cash
    val netChf: Double,
)

@Serializable
data class Goal(
    val name: String,                       // "Emergency savings", "Housing deposit"...
    val targetChf: Double,
    val targetDate: String,                 // "2029-12-31"
    val priority: Priority,
)

// A change decided by an event, applied at the start of its month. Exactly one group of fields is set.
@Serializable
data class PendingChange(
    val month: Int,
    val sourceEventId: String,
    val employment: Employment? = null,
    val factKey: FactKey? = null,
    val factValue: String? = null,
    val flag: String? = null,
    val household: Household? = null,
    val monthlyIncomeChf: Double? = null,           // the income becomes this
    val incomeDeltaChf: Double? = null,             // the income changes by this
    val incomeFactor: Double? = null,               // the income is multiplied by this
    val marketRentQuantile: Double? = null,         // the rent becomes the market rent (FlowModel.rentModel) at this quantile
    val expenseCategory: ExpenseCategory? = null,   // eventExpensesChf[category] changes by expenseDeltaChf
    val expenseDeltaChf: Double? = null,
    val loan: Loan? = null,                         // a new loan, paid out to cash (merged with an existing one)
)

fun FinancialStatement.netWorthChf(): Double = cashChf + investmentsChf - (loan?.principalChf ?: 0.0)
