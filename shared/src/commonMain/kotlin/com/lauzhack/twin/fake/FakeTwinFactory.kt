package com.lauzhack.twin.fake

import com.lauzhack.twin.engine.NewGame
import com.lauzhack.twin.engine.TwinFactory
import com.lauzhack.twin.model.AssetType
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.ExpenseStream
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.model.IncomeStream
import com.lauzhack.twin.model.Indexation
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.InterviewQuestion
import com.lauzhack.twin.model.Investment
import com.lauzhack.twin.model.Objective
import com.lauzhack.twin.model.Origin
import com.lauzhack.twin.model.Preferences
import com.lauzhack.twin.model.Priority
import com.lauzhack.twin.model.ProfileField
import com.lauzhack.twin.model.UserProfile

/**
 * P2 placeholder: turns interview answers into a profile, an opening statement and preferences.
 * The real version (Dev A + C) uses the nearest synthetic profile and the segment calibration.
 * Heuristics below are illustrative only (CHF).
 */
class FakeTwinFactory internal constructor(private val ledger: FakeLedger) : TwinFactory {

    constructor() : this(FakeLedger(FakeData.assumptions))

    override fun demo(): NewGame = NewGame(
        profile = FakeData.alexiaProfile,
        opening = ledger.opening(
            FakeData.alexiaProfile, 8_000.0, FakeData.alexiaInvestments,
            FakeData.alexiaIncome, FakeData.alexiaExpenses, FakeData.alexiaFacts,
        ),
        preferences = FakeData.alexiaPreferences,
    )

    override fun fromInterview(questions: List<InterviewQuestion>, answers: List<InterviewAnswer>): NewGame {
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
        val objective = fields[ProfileField.OBJECTIVE]?.let { Objective.valueOf(it) } ?: Objective.BALANCED
        val student = employment == Employment.NOT_WORKING && age < 30

        // Living costs: ~30 % of income, at least 700 CHF; family households spend more.
        val livingFactor = if (household == Household.FAMILY) 1.6 else 1.0
        val living = maxOf(700.0, income * 0.3) * livingFactor
        val insurance = if (household == Household.FAMILY) 900.0 else 320.0
        val minCash = fields[ProfileField.MIN_CASH]?.toDoubleOrNull() ?: ((rent + living + insurance) * 3)

        val goal = when (fields[ProfileField.GOAL_TYPE]) {
            "HOME" -> FakeData.goal("G-USER-01", "goal_home", maxOf(40_000.0, income * 12), 96, Priority.HIGH)
            "TRAVEL" -> FakeData.goal("G-USER-01", "goal_travel", 8_000.0, 36, Priority.MEDIUM)
            "RETIREMENT" -> FakeData.goal("G-USER-01", "goal_retirement", income * 24, 120, Priority.HIGH)
            else -> FakeData.goal("G-USER-01", "goal_emergency", (rent + living + insurance) * 6, 36, Priority.HIGH)
        }

        val profile = UserProfile(
            id = "USER-${answers.joinToString("") { it.optionId.take(2) }}",
            displayName = "",
            ageAtStart = age,
            city = "Lausanne",
            employment = employment,
            household = household,
            startMonth = "2025-01",
            goals = listOf(goal),
        )
        val facts = mapOf(
            FactKey.EDUCATION to if (student) "BACHELOR_IN_PROGRESS" else "UNKNOWN",
            FactKey.OCCUPATION to if (employment == Employment.NOT_WORKING || employment == Employment.UNEMPLOYED) "NONE" else "PROFESSIONAL",
            FactKey.CITY to "Lausanne",
            FactKey.HOUSING to if (household == Household.SHARED_HOUSING) "SHARED_FLAT" else "RENTED_FLAT",
            FactKey.FAMILY to when (household) {
                Household.COUPLE -> "COUPLE"
                Household.FAMILY -> "FAMILY"
                else -> "SINGLE"
            },
        )
        val opening = ledger.opening(
            profile = profile,
            cashChf = cash,
            investments = listOf(Investment("savings-3a", null, AssetType.INVESTMENT_PORTFOLIO, cash * 0.25, 0.01, "assumed")),
            income = listOf(IncomeStream("income", "income", income, indexation = Indexation.WAGES, origin = Origin.ASSUMED)),
            expenses = listOf(
                ExpenseStream("rent", ExpenseCategory.RENT, rent, indexation = Indexation.CPI, origin = Origin.ASSUMED),
                ExpenseStream("living", ExpenseCategory.LIVING, living, indexation = Indexation.CPI, origin = Origin.ASSUMED),
                ExpenseStream("insurance", ExpenseCategory.INSURANCE, insurance, indexation = Indexation.CPI, origin = Origin.ASSUMED),
            ),
            facts = facts,
        )
        val penalty = FakeData.assumptions.objectivePenalties[objective] ?: 50.0
        return NewGame(profile, opening, Preferences(objective, minCash, penalty))
    }
}
