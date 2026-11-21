package com.lauzhack.twin.interview

import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.model.InterviewAnswer
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

    // Same case as tools/test_interview.py (OpeningStatementTest): a student, everything not answered taken from the data.
    private val student = listOf(
        "age" to "a1", "city" to "lausanne", "household" to "single", "employment" to "not_working",
        "education" to "bachelor_in_progress", "income" to "idk", "rent" to "rdk", "cash" to "c2",
        "goal" to "emergency_savings", "objective" to "balanced", "mincash" to "m3",
    )

    private fun answers(vararg overrides: Pair<String, String>): List<InterviewAnswer> {
        val map = student.toMap() + overrides
        return catalog.questions.map { InterviewAnswer(it.id, map.getValue(it.id)) }
    }

    @Test
    fun everyProfileFieldIsAskedAndEveryOptionIsValid() {
        val asked = catalog.questions.flatMap { q -> q.options.flatMap { it.sets.keys } }.toSet()
        assertEquals(ProfileField.entries.toSet(), asked)
        for (q in catalog.questions) {
            assertTrue(q.options.size >= 2, q.id)
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
        for (id in listOf("income", "rent", "cash")) {
            assertTrue(catalog.questions.first { it.id == id }.options.any { it.sets.isEmpty() }, id)
        }
    }

    @Test
    fun studentWithDefaults() {
        val outcome = InterviewBuilder.build(catalog, answers(), facts, defaults)
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
        assertEquals(null, s.loan)
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
        val s = InterviewBuilder.build(catalog, answers("income" to "i4", "rent" to "r4"), facts, defaults).statement
        assertEquals(8750.0, s.monthlyIncomeChf)
        assertEquals(2250.0, s.monthlyRentChf)
    }

    @Test
    fun workingStudentKeepsBothAndSharedFlatSetsHousing() {
        val s = InterviewBuilder.build(
            catalog, answers("employment" to "part_time", "education" to "master_in_progress", "household" to "shared"), facts, defaults,
        ).statement
        assertEquals(Employment.PART_TIME, s.person.employment)
        assertEquals("MASTER_IN_PROGRESS", s.person.facts[FactKey.EDUCATION])
        assertEquals("PROFESSIONAL", s.person.facts[FactKey.OCCUPATION])
        assertEquals("SHARED_FLAT", s.person.facts[FactKey.HOUSING])
        assertEquals("SINGLE", s.person.facts[FactKey.FAMILY])
    }

    @Test
    fun goalIsSizedFromIncomeWithinTheDatasetRange() {
        val high = InterviewBuilder.build(catalog, answers("income" to "i5", "goal" to "long_term_financial_resilience"), facts, defaults)
        assertEquals(226800.0, high.statement.goals.single().targetChf)
        val low = InterviewBuilder.build(catalog, answers("income" to "i1"), facts, defaults)
        assertEquals(10000.0, low.statement.goals.single().targetChf)    // 8.7 x 950 is below the smallest goal in the dataset
    }

    @Test
    fun missingFieldIsReported() {
        assertFailsWith<IllegalArgumentException> {
            InterviewBuilder.build(catalog, answers().filter { it.questionId != "city" }, facts, defaults)
        }
        assertFailsWith<IllegalArgumentException> { InterviewBuilder.build(catalog, listOf(InterviewAnswer("nope", "x")), facts, defaults) }
    }

    @Test
    fun everyOptionBuildsAValidPerson() {
        for (q in catalog.questions) for (o in q.options) {
            val s = InterviewBuilder.build(catalog, answers(q.id to o.id), facts, defaults).statement
            assertTrue(s.cashChf >= 0 && s.monthlyIncomeChf >= 0 && s.monthlyRentChf > 0, "${q.id}/${o.id}")
            assertEquals(FactKey.entries.toSet(), s.person.facts.keys, "${q.id}/${o.id}")
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
