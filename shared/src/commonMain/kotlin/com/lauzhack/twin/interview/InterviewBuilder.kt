package com.lauzhack.twin.interview

import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Goal
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.Objective
import com.lauzhack.twin.model.Person
import com.lauzhack.twin.model.Preferences
import com.lauzhack.twin.model.Priority
import com.lauzhack.twin.model.ProfileField
import kotlin.math.round

// What the interview produces: the opening statement (month 0) and the user's preferences.
data class InterviewOutcome(
    val statement: FinancialStatement,
    val preferences: Preferences,
)

/**
 * P2: turns the interview answers into the person the game starts with. Pure function. The same rules as
 * tools/interview.py `person_from_fields` (tests/test_interview.py and InterviewBuilderTest use the same case).
 *
 *  - every answered option fills fields (`sets`); a later answer overrides an earlier one for the same field;
 *  - income, rent and cash not answered ("I don't know") take the dataset median: income by employment, rent by city x household
 *    (then city, then household), cash by age band;
 *  - the goal is sized as a multiple of the monthly income, as the dataset does, within the dataset's range;
 *  - the facts (OCCUPATION, HOUSING, FAMILY...) come from the `initialFromProfile` rules of facts.json, the same ones the
 *    dataset profiles use; studying is the EDUCATION fact and is independent from EMPLOYMENT;
 *  - there are no `ProfileField`s for investments or loans: they start at 0 / none.
 */
object InterviewBuilder {
    const val START_PERIOD = "2025-01-01"           // the dataset's as_of_date
    private const val IN_PROGRESS = "_IN_PROGRESS"
    private val REQUIRED = listOf(
        ProfileField.AGE, ProfileField.CITY, ProfileField.EMPLOYMENT, ProfileField.EDUCATION, ProfileField.HOUSEHOLD,
        ProfileField.GOAL_TYPE, ProfileField.OBJECTIVE, ProfileField.MIN_CASH,
    )

    fun fieldsFrom(catalog: InterviewCatalog, answers: List<InterviewAnswer>): Map<ProfileField, String> {
        val fields = mutableMapOf<ProfileField, String>()
        for (answer in answers) {
            val question = catalog.questions.firstOrNull { it.id == answer.questionId }
                ?: throw IllegalArgumentException("unknown question ${answer.questionId}")
            val option = question.options.firstOrNull { it.id == answer.optionId }
                ?: throw IllegalArgumentException("unknown option ${answer.optionId} in ${answer.questionId}")
            fields.putAll(option.sets)
        }
        return fields
    }

    fun build(
        catalog: InterviewCatalog,
        answers: List<InterviewAnswer>,
        facts: FactCatalog,
        defaults: InterviewDefaults,
        profileId: String = profileIdFor(answers),
    ): InterviewOutcome = build(fieldsFrom(catalog, answers), facts, defaults, profileId)

    fun build(
        fields: Map<ProfileField, String>,
        facts: FactCatalog,
        defaults: InterviewDefaults,
        profileId: String,
    ): InterviewOutcome {
        val missing = REQUIRED.filter { it !in fields }
        require(missing.isEmpty()) { "the interview left these fields empty: $missing" }

        val age = fields.getValue(ProfileField.AGE).toInt()
        val city = fields.getValue(ProfileField.CITY)
        val employment = Employment.valueOf(fields.getValue(ProfileField.EMPLOYMENT))
        val household = Household.valueOf(fields.getValue(ProfileField.HOUSEHOLD))
        val education = fields.getValue(ProfileField.EDUCATION)
        val objective = Objective.valueOf(fields.getValue(ProfileField.OBJECTIVE))
        require(education in facts.facts.getValue(FactKey.EDUCATION)) { "EDUCATION=$education is not in the facts catalog" }

        val income = fields[ProfileField.MONTHLY_INCOME]?.toDouble()
            ?: (defaults.incomeByEmployment[employment] ?: defaults.incomeOverall).chf
        val rent = fields[ProfileField.MONTHLY_RENT]?.toDouble() ?: defaultRent(defaults, city, household)
        val cash = fields[ProfileField.OPENING_CASH]?.toDouble() ?: defaultCash(defaults, age)

        val goalName = fields.getValue(ProfileField.GOAL_TYPE)
        val goalDefault = defaults.goals[goalName] ?: throw IllegalArgumentException("no dataset goal named $goalName")
        val (smallest, largest) = defaults.limits.goalTargetChf
        val target = (round(goalDefault.incomeRatio * income / 100.0) * 100.0).coerceIn(smallest, largest)

        // Initial facts: a student without a job is the dataset's STUDENT; EDUCATION is what the person answered.
        val studying = education.endsWith(IN_PROGRESS)
        val datasetEmployment = if (studying && employment == Employment.NOT_WORKING) "STUDENT" else employment.name
        val personFacts = initialFacts(datasetEmployment, household.name, city, facts).toMutableMap()
        personFacts[FactKey.EDUCATION] = education

        val statement = FinancialStatement(
            profileId = profileId,
            month = 0,
            period = START_PERIOD,
            person = Person(displayName = "", age = age, employment = employment, household = household, facts = personFacts, flags = emptyList()),
            cashChf = cash,
            investmentsChf = 0.0,
            loan = null,
            monthlyIncomeChf = income,
            monthlyRentChf = rent,
            eventExpensesChf = emptyMap(),
            cashFlow = null,
            goals = listOf(Goal(goalName, target, goalDefault.targetDate, Priority.valueOf(goalDefault.priority))),
            pendingChanges = emptyList(),
        )
        val preferences = Preferences(
            objective = objective,
            minCashChf = fields.getValue(ProfileField.MIN_CASH).toDouble(),
            penaltyChfPerMonthBelowMin = defaults.objectivePenaltyChfPerMonth.getValue(objective),
        )
        return InterviewOutcome(statement, preferences)
    }

    /** Stable id of a person from the interview: the same answers always give the same id. */
    fun profileIdFor(answers: List<InterviewAnswer>): String =
        "USER-" + answers.joinToString("|") { "${it.questionId}:${it.optionId}" }.hashCode().toUInt().toString(16)

    private fun defaultRent(d: InterviewDefaults, city: String, household: Household): Double =
        (d.rentByCityHousehold["$city|${household.name}"] ?: d.rentByCity[city] ?: d.rentByHousehold[household] ?: d.rentOverall).chf

    private fun defaultCash(d: InterviewDefaults, age: Int): Double {
        val band = d.ageBands.indexOfLast { age >= it.from }.coerceAtLeast(0)
        return (d.cashByAgeBand.getOrNull(band) ?: d.cashOverall).chf
    }

    /** The `initialFromProfile` rules of facts.json: the same facts a dataset profile gets from its row. */
    private fun initialFacts(datasetEmployment: String, household: String, city: String, catalog: FactCatalog): Map<FactKey, String> {
        val source = mapOf("datasetEmployment" to datasetEmployment, "household" to household, "city" to city)
        val result = mutableMapOf<FactKey, String>()
        for ((key, rule) in catalog.initialFromProfile) {
            val raw = source[rule.from] ?: throw IllegalArgumentException("unknown source '${rule.from}' in the rule for $key")
            val value = if (rule.map.isNotEmpty()) rule.map[raw] ?: rule.default else raw
            require(value != null && value in catalog.facts.getValue(key)) { "fact $key=$value (from '$raw') is not in the facts catalog" }
            result[key] = value
        }
        return result
    }
}
