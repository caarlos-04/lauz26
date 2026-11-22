package com.lauzhack.twin.engine

import com.lauzhack.twin.model.Better
import com.lauzhack.twin.model.Law
import com.lauzhack.twin.model.Quantile
import com.lauzhack.twin.model.Target
import com.lauzhack.twin.model.Uncertain
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.Action
import com.lauzhack.twin.model.LuckMode
import com.lauzhack.twin.model.NOTHING
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.FlowModel
import com.lauzhack.twin.model.Goal
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.Loan
import com.lauzhack.twin.model.Person
import com.lauzhack.twin.model.Priority
import com.lauzhack.twin.model.Requirements
import com.lauzhack.twin.model.netWorthChf
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Engine rules on a small hand-written model (no files needed). Same cases as tools/test_*.py.

private val json = Json { ignoreUnknownKeys = true }

private fun flow(key: String, direction: String, basis: String, category: String?, months: List<Int>, pBase: Double,
                 aBase: Double, pFactors: String = "{}", aFactors: String = "{}") = """
    {"key": "$key", "datasetCategory": "$key", "direction": "$direction", "basis": "$basis",
     "expenseCategory": ${category?.let { "\"$it\"" } ?: "null"}, "months": $months,
     "probability": {"base": $pBase, "factors": $pFactors},
     "amount": {"base": $aBase, "factors": $aFactors, "residualDeciles": [0.5,0.6,0.7,0.8,0.9,1.0,1.1,1.2,1.3,1.4,1.5],
                "residualMeanSquare": 1.0, "personLevelSd": 0.0},
     "nProfiles": 1, "nOccurrences": 1}"""

private val ALL = (1..12).toList()
private val MODEL: FlowModel = json.decodeFromString(
    """{"meta": {"generator": "test", "version": 1, "calibratedRange": "test", "features": [],
                 "numericFeatures": {"ageBand": "age"}, "bandEdges": {"age": [18, 25, 35]}},
        "cityCanton": {"Bern": "BE", "Geneva": "GE", "Lausanne": "VD"},
        "rentModel": {"base": 1000.0, "factors": {"household": {"SINGLE": 1.0, "SHARED_HOUSING": 0.5},
                                                  "city": {"Bern": 1.0, "Geneva": 1.5}},
                      "residualDeciles": [0.5,0.6,0.7,0.8,0.9,1.0,1.1,1.2,1.3,1.4,1.5], "nProfiles": 1},
        "flows": [${flow("salary", "IN", "INCOME", null, ALL, 1.0, 1.0)},
                  ${flow("rent", "OUT", "RENT", "RENT", ALL, 1.0, 1.0)},
                  ${flow("groceries", "OUT", "CHF", "LIVING", ALL, 1.0, 300.0,
                         aFactors = """{"city": {"Bern": 1.0, "Geneva": 1.25}, "studying": {"NO": 1.0, "YES": 0.8}}""")},
                  ${flow("tuition", "OUT", "CHF", "TUITION", listOf(2, 9), 1.0, 900.0, pFactors = """{"studying": {"NO": 0.0, "YES": 1.0}}""")},
                  ${flow("bonus", "IN", "INCOME", null, listOf(12), 1.0, 0.5, pFactors = """{"employment": {"EMPLOYED": 1.0, "NOT_WORKING": 0.0, "PART_TIME": 0.0}}""")}]}"""
)
private val SIM = Simulator(MODEL, macro = null)

private fun person(employment: Employment = Employment.EMPLOYED, city: String = "Bern", education: String = "UNKNOWN",
                   cash: Double = 10_000.0, income: Double = 5_000.0, rent: Double = 1_500.0, age: Int = 20,
                   loan: Loan? = null, goals: List<Goal> = emptyList()) = FinancialStatement(
    profileId = "T", month = 0, period = "2025-01-01",
    person = Person("Test", age, employment, Household.SINGLE,
                    mapOf(FactKey.EDUCATION to education, FactKey.OCCUPATION to "PROFESSIONAL", FactKey.CITY to city,
                          FactKey.HOUSING to "RENTED_FLAT", FactKey.FAMILY to "SINGLE"), emptyList()),
    cashChf = cash, investmentsChf = 0.0, loan = loan, monthlyIncomeChf = income, monthlyRentChf = rent,
    eventExpensesChf = emptyMap(), cashFlow = null, goals = goals, pendingChanges = emptyList())

private fun assertClose(expected: Double, actual: Double, tol: Double = 0.01, msg: String = "") =
    assertTrue(abs(expected - actual) <= tol, "$msg expected $expected, got $actual")

private fun after(s: FinancialStatement, months: Int) = SIM.run(s, months).last()

class PortableRandomTest {
    @Test fun sameNumbersAsPython() {           // values printed by tools/game.py
        assertEquals(16294208416658607535uL, PortableRandom.splitmix64(0uL))
        assertEquals(0.23279337494583152, PortableRandom.unit(1, 0, 1))
        assertEquals(0.40623052703226625, PortableRandom.unit(2026, 3, 2))
        assertEquals(0.7554293935800398, PortableRandom.unit(-7, 4, 1))
    }
}

class BasicsTest {
    @Test fun periods() {
        assertEquals("2025-01", nextPeriod("2025-01-01"))
        assertEquals("2025-02", nextPeriod("2025-01"))
        assertEquals("2026-01", nextPeriod("2025-12"))
    }

    @Test fun bands() {
        assertEquals("18-25", bandLabel(20.0, listOf(18.0, 25.0, 35.0)))
        assertEquals("<18", bandLabel(17.0, listOf(18.0, 25.0)))
        assertEquals("35+", bandLabel(70.0, listOf(18.0, 25.0, 35.0)))
        assertEquals("UNKNOWN", bandLabel(5.0, null))
    }

    @Test fun annuityRepaysTheLoan() {
        val pay = annuityPayment(10_000.0, 0.06, 60)
        var balance = 10_000.0
        repeat(60) { balance = balance * (1 + 0.06 / 12) - pay }
        assertClose(0.0, balance, 1e-6)
    }

    @Test fun probitMatchesTheNormalQuantiles() {     // values of Python's statistics.NormalDist().inv_cdf
        assertClose(-1.6448536269514729, probit(0.05), 1e-8)
        assertClose(-0.6744897501960817, probit(0.25), 1e-8)
        assertEquals(0.0, probit(0.5))
        assertClose(1.2815515655446008, probit(0.9), 1e-8)
    }

    @Test fun luckIsAPercentileOfEachUncertainNumber() {
        val salary = Uncertain("salary", Law.LOGNORMAL, 6100.0, Quantile(0.25, 4500.0), Quantile(0.75, 7500.0), Better.HIGH, 50.0, "chf",
                               listOf(Target(0, "monthlyChf")))
        assertEquals(listOf(4500.0, 6100.0, 7500.0), listOf(0.25, 0.5, 0.75).map { draw(salary, it) })   // through the data
        assertEquals(draw(salary, 0.95), draw(salary, 0.999))                                           // no draws beyond p95
        assertEquals(4500.0, draw(salary.copy(better = Better.LOW), 0.75))                               // a cost: good luck = low
        val r = resolve(listOf(Effect.SetIncome(6100.0)), listOf(salary), 0.75)
        assertEquals(Effect.SetIncome(7500.0), r.effects.single())
        assertEquals(mapOf("salary" to 7500.0), r.values)
        assertEquals("Start at CHF 7’500.", outcomeText(mapOf(Lang.EN to "Start at CHF {salary}.", Lang.ES to "x"), listOf(salary), r.values, Lang.EN))
        assertEquals("+30 %", formatValue("change", 1.2995, Lang.EN))
        assertEquals("5,8 %", formatValue("percent", 0.0582, Lang.ES))
        assertEquals("a precio normal", formatValue("flat", 0.5, Lang.ES))
    }

    @Test fun portableHashAndNodeLuck() {          // values printed by tools/game.py
        assertEquals(0xCBF29CE484222325uL, PortableRandom.fnv1a64(""))
        assertEquals(0xAF63DC4C8601EC8CuL, PortableRandom.fnv1a64("a"))
        val same = listOf("A-master", "A-start-working", NOTHING).map { PortableRandom.nodeLuck(7, LuckMode.SAME_LEVEL, it, 0) }
        assertEquals(1, same.toSet().size)
        val own = listOf("A-master", "A-start-working", NOTHING).map { PortableRandom.nodeLuck(7, LuckMode.FULL_RANDOM, it, 0) }
        assertEquals(3, own.toSet().size)
    }

    @Test fun netWorthAndGoalsAreComputed() {
        val s = person(cash = 12_000.0, loan = Loan(3_000.0, 0.05, 100.0), goals = listOf(
            Goal("Emergency savings", 10_000.0, "2029-12-31", Priority.HIGH),
            Goal("Long-term financial resilience", 15_000.0, "2029-12-31", Priority.MEDIUM)))
        assertEquals(9_000.0, s.netWorthChf())
        assertEquals(listOf(GoalState.REACHED, GoalState.IN_PROGRESS), s.goals.map { goalState(it, s) })
    }
}

class SimulatorTest {
    @Test fun expectedMonthIsTheSumOfTheFlows() {
        val s1 = SIM.step(person())
        assertClose(5_000.0 - 1_500.0 - 300.0, s1.cashFlow!!.netChf)
        assertEquals("2025-01", s1.period)
        assertClose(10_000.0 + 3_200.0, s1.cashChf)
    }

    @Test fun tuitionOnlyWhenStudyingInFebruaryAndSeptember() {
        val months = SIM.run(person(Employment.NOT_WORKING, education = "BACHELOR_IN_PROGRESS"), 12)
        assertEquals(listOf(2, 9), months.drop(1).filter { it.cashFlow!!.expensesChf.containsKey("tuition") }.map { it.month })
        assertTrue(SIM.run(person(), 12).drop(1).none { it.cashFlow!!.expensesChf.containsKey("tuition") })
    }

    @Test fun decemberBonusDependsOnEmployment() {
        assertClose(2_500.0, after(person(), 12).cashFlow!!.incomeChf.getValue("bonus"))
        assertNull(after(person(Employment.PART_TIME), 12).cashFlow!!.incomeChf["bonus"])
    }

    @Test fun ageGrowsEveryTwelveMonths() {
        val sts = SIM.run(person(age = 20), 24)
        assertEquals(listOf(20, 20, 21, 21, 22), listOf(0, 11, 12, 23, 24).map { sts[it].person.age })
    }

    @Test fun negativeCashPaysFinancingAndTheLoanIsRepaid() {
        val sim = Simulator(MODEL, null, EngineRules(financingRateAnnual = 0.12))
        val s1 = sim.step(person(cash = -12_000.0, income = 1_000.0, rent = 400.0))
        assertClose(-12_000.0 + 1_000.0 - 400.0 - 300.0 - 120.0, s1.cashChf)
        val loanEnd = SIM.run(person(loan = Loan(5_000.0, 0.05, 200.0)), 40).last()
        assertEquals(0.0, loanEnd.loan!!.principalChf)
    }

    @Test fun unknownCityUsesTheReferenceLevel() {
        assertClose(300.0, SIM.step(person(city = "ABROAD")).cashFlow!!.expensesChf.getValue("groceries"))
    }
}

class EventEffectsTest {
    @Test fun incomeFactorIsUndoneAfterItsMonths() {
        val s = applyEffects(person(income = 1_000.0), listOf(Effect.IncomeFactor(0.5, months = 3)), "E")
        assertEquals(listOf(500.0, 500.0, 500.0, 1_000.0, 1_000.0), SIM.run(s, 5).drop(1).map { it.monthlyIncomeChf })
    }

    @Test fun temporaryExpenseAndOneOffEndExactly() {
        val s = applyEffects(person(income = 1_000.0, rent = 400.0, city = "Bern"), listOf(
            Effect.ExpenseDelta(ExpenseCategory.TUITION, 100.0, months = 3), Effect.OneOff(500.0, startOffsetMonths = 5)), "E")
        val end = after(s, 10)
        assertClose(10_000.0 + 10 * (1_000.0 - 400.0 - 300.0) - 3 * 100.0 - 500.0, end.cashChf)
        assertTrue(end.eventExpensesChf.isEmpty())
    }

    @Test fun eventTuitionReplacesTheLearnedTuition() {
        val student = person(Employment.NOT_WORKING, education = "BACHELOR_IN_PROGRESS")
        val s = applyEffects(student, listOf(Effect.ExpenseDelta(ExpenseCategory.TUITION, 500.0, months = 12)), "E")
        val sts = SIM.run(s, 12).drop(1)
        assertTrue(sts.none { it.cashFlow!!.expensesChf.containsKey("tuition") })
        assertTrue(sts.all { it.cashFlow!!.expensesChf["TUITION"] == 500.0 })
    }

    @Test fun loanIsPaidOutInTheFirstMonthAndMerged() {
        val s = applyEffects(person(loan = Loan(10_000.0, 0.04, 300.0)), listOf(Effect.AddLiability(10_000.0, 0.08, 60)), "E")
        val m1 = SIM.step(s)
        assertEquals(10_000.0, m1.cashFlow!!.incomeChf["loan"])
        assertClose(0.06, m1.loan!!.annualRate, 1e-9)
    }

    @Test fun movingSetsTheMarketRentOfTheNewCityAndHousehold() {
        val moved = applyEffects(person(rent = 1_800.0), listOf(Effect.SetFact(FactKey.CITY, "Geneva"), Effect.MarketRent(0.5)), "E")
        assertClose(1_500.0, SIM.step(moved).monthlyRentChf)
        val shared = applyEffects(person(rent = 1_800.0), listOf(Effect.SetHousehold(Household.SHARED_HOUSING),
                                                                 Effect.MarketRent(0.75)), "E")
        val m1 = SIM.step(shared)
        assertEquals(Household.SHARED_HOUSING, m1.person.household)
        assertClose(1_000.0 * 0.5 * 1.25, m1.monthlyRentChf)
    }

    @Test fun lifeChangesApplyAtTheStartOfTheirMonth() {
        val s = applyEffects(person(Employment.NOT_WORKING), listOf(
            Effect.SetEmployment(Employment.EMPLOYED, startOffsetMonths = 3), Effect.AddFlag("x", startOffsetMonths = 3)), "E")
        val sts = SIM.run(s, 4)
        assertEquals(Employment.NOT_WORKING, sts[3].person.employment)
        assertEquals(Employment.EMPLOYED, sts[4].person.employment)
        assertTrue("x" in sts[4].person.flags && sts[4].pendingChanges.isEmpty())
    }

    @Test fun requirements() {
        val ev = Action("E", "c", mapOf(), mapOf(), Requirements(maxCashChf = 0.0, household = listOf(Household.SINGLE),
                                                                 facts = mapOf(FactKey.EDUCATION to listOf("UNKNOWN"))),
                        sources = listOf("s"), outcome = mapOf())
        assertFalse(isEligible(ev, person(cash = 100.0), 0, emptySet()))
        assertTrue(isEligible(ev, person(cash = -100.0), 0, emptySet()))
        assertFalse(isEligible(ev, person(cash = -100.0), 0, setOf("E")))       // not repeatable
    }
}
