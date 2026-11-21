package ch.lauzhack.twin.fake

import ch.lauzhack.twin.model.Asset
import ch.lauzhack.twin.model.AssetType
import ch.lauzhack.twin.model.Employment
import ch.lauzhack.twin.model.ExpenseCategory
import ch.lauzhack.twin.model.ExpenseStream
import ch.lauzhack.twin.model.FinancialState
import ch.lauzhack.twin.model.Goal
import ch.lauzhack.twin.model.Household
import ch.lauzhack.twin.model.IncomeStream
import ch.lauzhack.twin.model.InterviewAnswer
import ch.lauzhack.twin.model.InterviewQuestion
import ch.lauzhack.twin.model.Priority
import ch.lauzhack.twin.model.ProfileField
import ch.lauzhack.twin.model.UserProfile

/**
 * P2 placeholder: turns interview answers into a profile + initial state.
 * The real version (Dev B + C) will use the nearest synthetic profile and the segment calibration.
 * Heuristics below are illustrative only (CHF).
 */
object FakeProfileBuilder {

    fun build(questions: List<InterviewQuestion>, answers: List<InterviewAnswer>): Pair<UserProfile, FinancialState> {
        val fields = mutableMapOf<ProfileField, String>()
        for (answer in answers) {
            val option = questions.firstOrNull { it.id == answer.questionId }
                ?.options?.firstOrNull { it.id == answer.optionId } ?: continue
            fields.putAll(option.sets)
        }
        val age = fields[ProfileField.AGE]?.toIntOrNull() ?: 30
        val employment = fields[ProfileField.EMPLOYMENT]?.let { Employment.valueOf(it) } ?: Employment.EMPLOYED
        val household = fields[ProfileField.HOUSEHOLD]?.let { Household.valueOf(it) } ?: Household.SINGLE
        val income = fields[ProfileField.MONTHLY_INCOME]?.toDoubleOrNull() ?: 3_000.0
        val rent = fields[ProfileField.MONTHLY_RENT]?.toDoubleOrNull() ?: 1_300.0
        val cash = fields[ProfileField.OPENING_CASH]?.toDoubleOrNull() ?: 8_000.0

        // Living costs: ~30 % of income, at least 700 CHF; family households spend more.
        val livingFactor = if (household == Household.FAMILY) 1.6 else 1.0
        val living = maxOf(700.0, income * 0.3) * livingFactor
        val insurance = if (household == Household.FAMILY) 900.0 else 320.0

        val goal = when (fields[ProfileField.GOAL_TYPE]) {
            "HOME" -> Goal("G-USER-01", "goal_home", maxOf(40_000.0, income * 12), 96, Priority.HIGH)
            "TRAVEL" -> Goal("G-USER-01", "goal_travel", 8_000.0, 36, Priority.MEDIUM)
            "RETIREMENT" -> Goal("G-USER-01", "goal_retirement", income * 24, 120, Priority.HIGH)
            else -> Goal("G-USER-01", "goal_emergency", (rent + living + insurance) * 6, 36, Priority.HIGH)
        }

        val profile = UserProfile(
            id = "USER-${answers.joinToString("") { it.optionId.take(2) }}",
            displayName = "",
            ageAtStart = age,
            city = "Lausanne",
            canton = "VD",
            employment = employment,
            household = household,
            startMonth = "2025-01",
            goals = listOf(goal),
        )
        val state = FinancialState(
            month = 0,
            cash = cash,
            assets = listOf(Asset("savings-3a", AssetType.INVESTMENT_PORTFOLIO, cash * 0.25, 0.01)),
            liabilities = emptyList(),
            income = listOf(IncomeStream(if (employment == Employment.STUDENT) "job" else "salary", "Income", income)),
            expenses = listOf(
                ExpenseStream("rent", ExpenseCategory.RENT, rent),
                ExpenseStream("living", ExpenseCategory.LIVING, living),
                ExpenseStream("insurance", ExpenseCategory.INSURANCE, insurance),
            ),
            employment = employment,
        )
        return profile to state
    }
}
