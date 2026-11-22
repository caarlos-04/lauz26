package com.lauzhack.twin.interview

import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.engine.annuityPayment
import com.lauzhack.twin.engine.round2
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.netWorthChf
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import com.lauzhack.twin.model.Objective
import com.lauzhack.twin.model.Priority
import com.lauzhack.twin.model.ProfileField
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// content/interview*.json are written by tools/interview.py from the dataset. Here we check that the shared model
// decodes them, that the questions cover every ProfileField and that every answer builds a valid person.
class InterviewBuilderTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "content").isDirectory }
    private fun content(name: String) = File(repoRoot, "content/$name").readText()

    private val catalog = json.decodeFromString<InterviewCatalog>(content("interview.json"))
    private val defaults = json.decodeFromString<InterviewDefaults>(content("interview-defaults.json"))
    private val facts = json.decodeFromString<FactCatalog>(content("facts.json"))
    private val penalties = json.decodeFromString<Assumptions>(content("assumptions.json")).objectivePenalties

    // Same case as tools/test_interview.py (OpeningStatementTest): a student, everything not answered taken from the data.
    // A choice is the option id; a typed number is a Double.
    private val student = listOf<Pair<String, Any>>(
        "age" to 21.0, "city" to "lausanne", "household" to "single", "employment" to "not_working",
        "education" to "bachelor_in_progress", "income" to "dk", "rent" to "dk", "cash" to 9000.0,
        "investments" to 0.0, "debt" to 0.0, "goal" to "emergency_savings", "objective" to "balanced", "mincash" to 3500.0,
    )

    private fun answers(vararg overrides: Pair<String, Any>): List<InterviewAnswer> {
        val map = student.toMap() + overrides
        return catalog.questions.map { q ->
            when (val a = map.getValue(q.id)) {
                is Double -> InterviewAnswer(q.id, value = a)
                else -> InterviewAnswer(q.id, optionId = a as String)
            }
        }
    }

    @Test
    fun everyProfileFieldIsAskedAndEveryOptionIsValid() {
        val asked = catalog.questions.flatMap { q -> q.options.flatMap { it.sets.keys } + listOfNotNull(q.input?.field) }.toSet()
        assertEquals(ProfileField.entries.toSet(), asked)
        for (q in catalog.questions) {
            val spec = q.input
            if (spec == null) assertTrue(q.options.size >= 2, q.id)
            else assertTrue(spec.min <= spec.typical && spec.typical <= spec.max && spec.step > 0, q.id)
            assertEquals(q.options.size, q.options.map { it.id }.toSet().size, "${q.id}: duplicate option ids")
            for (o in q.options) for ((field, value) in o.sets) {
                val where = "${q.id}/${o.id}"
                when (field) {
                    ProfileField.EMPLOYMENT -> Employment.valueOf(value)
                    ProfileField.HOUSEHOLD -> Household.valueOf(value)
                    ProfileField.OBJECTIVE -> Objective.valueOf(value)
                    ProfileField.EDUCATION -> assertTrue(value in facts.facts.getValue(FactKey.EDUCATION), where)
                    ProfileField.CITY -> assertTrue(value in facts.facts.getValue(FactKey.CITY), where)
                    ProfileField.GOAL_TYPE -> assertTrue(value in defaults.goals, where)
                    else -> assertTrue(value.toDouble() >= 0.0, where)
                }
            }
        }
    }

    @Test
    fun numericQuestionsOfferIDontKnow() {
        for (id in listOf("income", "rent", "cash", "investments", "debt")) {
            assertTrue(catalog.questions.first { it.id == id }.options.any { it.sets.isEmpty() }, id)
        }
    }

    @Test
    fun studentWithDefaults() {
        val outcome = InterviewBuilder.build(catalog, answers(), facts, defaults, penalties)
        val s = outcome.statement
        assertEquals(21, s.person.age)
        assertEquals(Employment.NOT_WORKING, s.person.employment)
        assertEquals(Household.SINGLE, s.person.household)
        assertEquals(
            mapOf(
                FactKey.EDUCATION to "BACHELOR_IN_PROGRESS", FactKey.OCCUPATION to "NONE", FactKey.HOUSING to "RENTED_FLAT",
                FactKey.FAMILY to "SINGLE", FactKey.CITY to "Lausanne",
            ),
            s.person.facts,
        )
        assertEquals(1200.0, s.monthlyIncomeChf)    // default: median income of the dataset's students
        assertEquals(1590.0, s.monthlyRentChf)      // default: median rent, Lausanne x SINGLE
        assertEquals(9000.0, s.cashChf)
        assertEquals(0.0, s.investmentsChf)
        assertEquals(null, s.loan)                  // typed 0
        assertEquals(0, s.month)
        assertEquals("2025-01-01", s.period)
        val goal = s.goals.single()
        assertEquals("Emergency savings", goal.name)
        assertEquals(10400.0, goal.targetChf)
        assertEquals("2029-12-31", goal.targetDate)
        assertEquals(Priority.HIGH, goal.priority)
        assertEquals(Objective.BALANCED, outcome.preferences.objective)
        assertEquals(3500.0, outcome.preferences.minCashChf)
        assertEquals(500.0, outcome.preferences.penaltyChfPerMonthBelowMin)
    }

    @Test
    fun answersOverrideDefaults() {
        val s = InterviewBuilder.build(catalog, answers("income" to 8750.0, "rent" to 2250.0), facts, defaults, penalties).statement
        assertEquals(8750.0, s.monthlyIncomeChf)
        assertEquals(2250.0, s.monthlyRentChf)
    }

    @Test
    fun investmentsAndDebt() {
        val s = InterviewBuilder.build(catalog, answers("investments" to 20000.0, "debt" to 12000.0), facts, defaults, penalties).statement
        assertEquals(20000.0, s.investmentsChf)
        val loan = assertNotNull(s.loan)
        assertEquals(12000.0, loan.principalChf)
        assertEquals(defaults.loan.annualRate, loan.annualRate)
        assertEquals(round2(annuityPayment(12000.0, defaults.loan.annualRate, defaults.loan.termMonths)), loan.monthlyPaymentChf)
        assertEquals(20000.0 + 9000.0 - 12000.0, s.netWorthChf())
        val dk = InterviewBuilder.build(catalog, answers("investments" to "dk", "debt" to "dk"), facts, defaults, penalties).statement
        assertEquals(defaults.investmentsByAgeBand[0].chf, dk.investmentsChf)       // 18-24
        assertNull(dk.loan)                                                         // most people owe nothing
    }

    @Test
    fun aTypedNumberMustBeInRange() {
        assertFailsWith<IllegalArgumentException> { InterviewBuilder.build(catalog, answers("income" to 999_999.0), facts, defaults, penalties) }
        assertEquals("1500", numberText(1500.0))
        assertEquals("12.5", numberText(12.5))
    }

    @Test
    fun workingStudentKeepsBothAndSharedFlatSetsHousing() {
        val s = InterviewBuilder.build(
            catalog, answers("employment" to "part_time", "education" to "master_in_progress", "household" to "shared"), facts, defaults, penalties,
        ).statement
        assertEquals(Employment.PART_TIME, s.person.employment)
        assertEquals("MASTER_IN_PROGRESS", s.person.facts[FactKey.EDUCATION])
        assertEquals("PROFESSIONAL", s.person.facts[FactKey.OCCUPATION])
        assertEquals("SHARED_FLAT", s.person.facts[FactKey.HOUSING])
        assertEquals("SINGLE", s.person.facts[FactKey.FAMILY])
    }

    @Test
    fun goalIsSizedFromIncomeWithinTheDatasetRange() {
        val high = InterviewBuilder.build(catalog, answers("income" to 10750.0, "goal" to "long_term_financial_resilience"), facts, defaults, penalties)
        assertEquals(226800.0, high.statement.goals.single().targetChf)
        val low = InterviewBuilder.build(catalog, answers("income" to 950.0), facts, defaults, penalties)
        assertEquals(10000.0, low.statement.goals.single().targetChf)    // 8.7 x 950 is below the smallest goal in the dataset
    }

    @Test
    fun missingFieldIsReported() {
        assertFailsWith<IllegalArgumentException> {
            InterviewBuilder.build(catalog, answers().filter { it.questionId != "city" }, facts, defaults, penalties)
        }
        assertFailsWith<IllegalArgumentException> { InterviewBuilder.build(catalog, listOf(InterviewAnswer("nope", "x")), facts, defaults, penalties) }
    }

    @Test
    fun everyOptionAndBoundBuildsAValidPerson() {
        for (q in catalog.questions) {
            val tries: List<Any> = q.options.map { it.id } + listOfNotNull(q.input?.min, q.input?.max)
            for (x in tries) {
                val s = InterviewBuilder.build(catalog, answers(q.id to x), facts, defaults, penalties).statement
                assertTrue(s.cashChf >= 0 && s.monthlyIncomeChf >= 0 && s.monthlyRentChf >= 0, "${q.id}=$x")
                assertEquals(FactKey.entries.toSet(), s.person.facts.keys, "${q.id}=$x")
            }
        }
    }

    @Test
    fun profileIdIsStableAndDependsOnTheAnswers() {
        val a = answers()
        assertEquals(InterviewBuilder.profileIdFor(a), InterviewBuilder.profileIdFor(answers()))
        assertTrue(InterviewBuilder.profileIdFor(a) != InterviewBuilder.profileIdFor(answers("city" to "zurich")))
        assertTrue(InterviewBuilder.profileIdFor(a).startsWith("USER-"))
    }
}
