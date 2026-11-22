package com.lauzhack.twin.fake

import com.lauzhack.twin.engine.NewGame
import com.lauzhack.twin.engine.TwinFactory
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.InterviewQuestion
import com.lauzhack.twin.model.Objective
import com.lauzhack.twin.model.Person
import com.lauzhack.twin.model.Preferences
import com.lauzhack.twin.model.Priority
import com.lauzhack.twin.model.ProfileField

/**
 * P1-P2 placeholder for `statement.new_person` (docs/financial-statement.md section 3): the interview answers become
 * the opening [FinancialStatement] (month 0). Nothing is copied from a dataset profile. Dev A ports the real version
 * (initial facts from content/facts.json rules); the fallbacks below are illustrative only (CHF).
 */
class FakeTwinFactory : TwinFactory {

    override fun demo(): NewGame = NewGame(FakeData.alexia, FakeData.alexiaPreferences)

    override fun fromInterview(questions: List<InterviewQuestion>, answers: List<InterviewAnswer>): NewGame {
        val fields = com.lauzhack.twin.interview.fieldsOf(questions, answers)
        val age = fields[ProfileField.AGE]?.toIntOrNull() ?: 30
        val city = fields[ProfileField.CITY] ?: "Lausanne"
        val employment = fields[ProfileField.EMPLOYMENT]?.let { Employment.valueOf(it) } ?: Employment.EMPLOYED
        val education = fields[ProfileField.EDUCATION] ?: "UNKNOWN"
        val household = fields[ProfileField.HOUSEHOLD]?.let { Household.valueOf(it) } ?: Household.SINGLE
        val income = fields[ProfileField.MONTHLY_INCOME]?.toDoubleOrNull() ?: 3_000.0
        val rent = fields[ProfileField.MONTHLY_RENT]?.toDoubleOrNull() ?: 1_300.0
        val cash = fields[ProfileField.OPENING_CASH]?.toDoubleOrNull() ?: 8_000.0
        val investments = fields[ProfileField.INVESTMENTS]?.toDoubleOrNull() ?: 0.0
        val objective = fields[ProfileField.OBJECTIVE]?.let { Objective.valueOf(it) } ?: Objective.BALANCED
        val minCash = fields[ProfileField.MIN_CASH]?.toDoubleOrNull() ?: (rent * 3)

        val goal = when (fields[ProfileField.GOAL_TYPE]) {
            "HOME" -> FakeData.goal("Housing deposit", maxOf(40_000.0, income * 12), 96, Priority.HIGH)
            "TRAVEL" -> FakeData.goal("Travel fund", 8_000.0, 36, Priority.MEDIUM)
            "RETIREMENT" -> FakeData.goal("Retirement savings", income * 24, 120, Priority.HIGH)
            else -> FakeData.goal("Emergency savings", rent * 6 + 6_000.0, 36, Priority.HIGH)
        }

        // Same mapping as content/facts.json initialFromProfile, with studying as its own answer.
        val facts = mapOf(
            FactKey.EDUCATION to education,
            FactKey.OCCUPATION to when (employment) {
                Employment.EMPLOYED, Employment.PART_TIME, Employment.SELF_EMPLOYED -> "PROFESSIONAL"
                else -> "NONE"
            },
            FactKey.CITY to city,
            FactKey.HOUSING to if (household == Household.SHARED_HOUSING) "SHARED_FLAT" else "RENTED_FLAT",
            FactKey.FAMILY to when (household) {
                Household.COUPLE -> "COUPLE"
                Household.FAMILY -> "FAMILY"
                else -> "SINGLE"
            },
        )
        val opening = FinancialStatement(
            profileId = "USER-${answers.joinToString("") { (it.optionId ?: it.value?.toLong()?.toString()).orEmpty().take(2) }}",
            month = 0,
            period = "2025-01-01",
            person = Person(displayName = "", age = age, employment = employment, household = household, facts = facts, flags = emptyList()),
            cashChf = cash,
            investmentsChf = investments,
            loan = null,
            monthlyIncomeChf = income,
            monthlyRentChf = rent,
            eventExpensesChf = emptyMap(),
            cashFlow = null,
            goals = listOf(goal),
            pendingChanges = emptyList(),
        )
        val penalty = FakeData.assumptions.objectivePenalties[objective] ?: 50.0
        return NewGame(opening, Preferences(objective, minCash, penalty))
    }
}
