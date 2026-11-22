package com.lauzhack.twin.fake

import com.lauzhack.twin.engine.EngineRules
import com.lauzhack.twin.engine.RealGameEngine
import com.lauzhack.twin.engine.Simulator
import com.lauzhack.twin.engine.TwinEngine
import com.lauzhack.twin.model.ActionCatalog
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.FlowModel
import com.lauzhack.twin.model.Goal
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.model.InputUnit
import com.lauzhack.twin.model.InterviewOption
import com.lauzhack.twin.model.InterviewQuestion
import com.lauzhack.twin.model.NumberInput
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.LocalizedText
import com.lauzhack.twin.model.Objective
import com.lauzhack.twin.model.Person
import com.lauzhack.twin.model.Preferences
import com.lauzhack.twin.model.Priority
import com.lauzhack.twin.model.ProfileField
import kotlinx.serialization.json.Json

/**
 * Placeholder data so the UI runs before the engine and the full content exist.
 * ALL values are synthetic and illustrative (CHF, rough Swiss orders of magnitude), not calibrated.
 * Fact codes and flags follow content/facts.json. Goal names follow synthetic-goals ("Emergency savings"...).
 */
object FakeData {

    private fun t(en: String, es: String): LocalizedText = mapOf(Lang.EN to en, Lang.ES to es)

    /** "2025-01" + 36 months -> "2027-12-31" (end of the target month, docs 5.2). */
    internal fun targetDate(startMonth: String, targetMonth: Int): String {
        val (y, m) = startMonth.split("-").map { it.toInt() }
        val index = (y * 12 + (m - 1)) + targetMonth - 1
        val year = index / 12
        val month = index % 12 + 1
        val days = when (month) { 2 -> 28; 4, 6, 9, 11 -> 30; else -> 31 }
        return "$year-${month.toString().padStart(2, '0')}-$days"
    }

    internal fun goal(name: String, target: Double, month: Int, priority: Priority, start: String = "2025-01") =
        Goal(name, target, targetDate(start, month), priority)

    // ---------- Assumptions ----------

    val assumptions = Assumptions(
        investmentReturnAnnual = 0.04,
        financingRateAnnual = 0.085,
        objectivePenalties = mapOf(Objective.GROWTH to 0.0, Objective.BALANCED to 50.0, Objective.SAFE to 150.0),
        sensitivity = emptyList(),
        // Shown verbatim in the UI; Dev C will replace them with content/assumptions.json.
        sourceNotes = listOf(
            "Fake engine: flat income and rent, living costs by household (placeholder, not the learned flow model).",
            "Investment return 4 %/yr.",
            "Negative cash is financed at 8.5 %/yr (max rate in synthetic-liabilities.csv).",
            "Case probabilities 20 % / 60 % / 20 % by default.",
            "No taxes are modelled.",
        ),
    )

    // ---------- Facts catalogue (subset of content/facts.json, used for labels) ----------

    val facts = FactCatalog(
        version = 1,
        facts = mapOf(
            FactKey.EDUCATION to mapOf(
                "UNKNOWN" to t("Unknown", "Desconocida"),
                "BACHELOR_IN_PROGRESS" to t("Studying a bachelor", "Estudiando un grado"),
                "BACHELOR" to t("Bachelor's degree", "Grado"),
                "MASTER_IN_PROGRESS" to t("Studying a master", "Estudiando un máster"),
                "MASTER" to t("Master's degree", "Máster"),
            ),
            FactKey.OCCUPATION to mapOf(
                "NONE" to t("No job", "Sin trabajo"),
                "JUNIOR" to t("Junior job", "Trabajo junior"),
                "PROFESSIONAL" to t("Professional", "Profesional"),
                "SENIOR" to t("Senior", "Sénior"),
            ),
            FactKey.CITY to mapOf("Lausanne" to t("Lausanne", "Lausana"), "Geneva" to t("Geneva", "Ginebra")),
            FactKey.HOUSING to mapOf(
                "WITH_PARENTS" to t("Living with parents", "Con los padres"),
                "SHARED_FLAT" to t("Shared flat", "Piso compartido"),
                "RENTED_FLAT" to t("Rented flat", "Piso de alquiler"),
                "OWNED_HOME" to t("Own home", "Vivienda propia"),
            ),
            FactKey.FAMILY to mapOf(
                "SINGLE" to t("Single", "Soltero/a"),
                "COUPLE" to t("In a couple", "En pareja"),
                "FAMILY" to t("Family", "Familia"),
            ),
        ),
        flags = mapOf(
            "did_master" to t("Did a master's degree", "Hizo un máster"),
            "started_working_early" to t("Started working early", "Empezó a trabajar pronto"),
            "took_loan" to t("Took a loan", "Pidió un préstamo"),
            "rented_own_flat" to t("Rented a flat of their own", "Alquiló un piso propio"),
            "promoted" to t("Got promoted", "Ascendió"),
        ),
        initialFromProfile = emptyMap(),
    )

    // ---------- Alexia (demo profile) ----------

    val alexiaGoals = listOf(
        goal("Emergency savings", 10_000.0, 36, Priority.HIGH),
        goal("Travel fund", 6_000.0, 60, Priority.MEDIUM),
        goal("Housing deposit", 40_000.0, 108, Priority.MEDIUM),
    )

    val alexiaPreferences = Preferences(Objective.BALANCED, minCashChf = 3_000.0, penaltyChfPerMonthBelowMin = 50.0)

    /** Opening statement (month 0) of the demo profile: 20, studying a bachelor and working part time, Lausanne. */
    val alexia = FinancialStatement(
        profileId = "DEMO-ALEXIA",
        month = 0,
        period = "2025-01-01",
        person = Person(
            displayName = "Alexia",
            age = 20,
            employment = Employment.PART_TIME,
            household = Household.SHARED_HOUSING,
            facts = mapOf(
                FactKey.EDUCATION to "BACHELOR_IN_PROGRESS",
                FactKey.OCCUPATION to "NONE",
                FactKey.CITY to "Lausanne",
                FactKey.HOUSING to "SHARED_FLAT",
                FactKey.FAMILY to "SINGLE",
            ),
            flags = emptyList(),
        ),
        cashChf = 8_000.0,
        investmentsChf = 0.0,
        loan = null,
        monthlyIncomeChf = 2_000.0,
        monthlyRentChf = 800.0,
        eventExpensesChf = mapOf(ExpenseCategory.TUITION to 120.0),
        cashFlow = null,
        goals = alexiaGoals,
        pendingChanges = emptyList(),
    )

    // ---------- Action pool and a small flow model (the real engine on hand-written numbers) ----------

    private const val FAKE_SOURCE = "Fake data for the UI (illustrative, not calibrated)."

    private val json = Json { ignoreUnknownKeys = true }

    private fun action(id: String, en: String, es: String, requires: String, effects: String, outcomeEn: String, outcomeEs: String,
                       uncertain: String = "[]", repeatable: Boolean = false) = """
        {"id": "$id", "category": "Fake", "title": {"EN": "$en", "ES": "$es"}, "description": {"EN": "$en.", "ES": "$es."},
         "requires": $requires, "repeatable": $repeatable, "sources": ["$FAKE_SOURCE"], "effects": $effects,
         "uncertain": $uncertain, "outcome": {"EN": "$outcomeEn", "ES": "$outcomeEs"}}"""

    /** A salary drawn at the luck of the decision: p25 4'500, median 6'000, p75 7'500 (illustrative). */
    private fun salary(effect: Int) = """[{"name": "salary", "law": "LOGNORMAL", "median": 6000, "low": {"q": 0.25, "value": 4500},
        "high": {"q": 0.75, "value": 7500}, "better": "HIGH", "step": 50, "format": "chf", "targets": [{"effect": $effect, "field": "monthlyChf"}]}]"""

    /** A few actions with the shape of content/events.json (NOTHING, studies, work, a flat, a loan) and a job loss. */
    val events: ActionCatalog = json.decodeFromString(
        """{"version": 5, "actions": [
        ${action("NOTHING", "Carry on as you are", "Seguir como estás", "null", "[]",
                 "Two more years of the same life.", "Dos años más de la misma vida.", repeatable = true)},
        ${action("A-master", "Do a master's", "Hacer un máster", """{"facts": {"EDUCATION": ["BACHELOR_IN_PROGRESS"]}}""",
                 """[{"type": "expense_delta", "category": "TUITION", "deltaChf": 400, "months": 24},
                    {"type": "set_fact", "key": "EDUCATION", "value": "MASTER_IN_PROGRESS"},
                    {"type": "set_fact", "key": "EDUCATION", "value": "MASTER", "startOffsetMonths": 24},
                    {"type": "set_employment", "employment": "EMPLOYED", "startOffsetMonths": 24},
                    {"type": "set_income", "monthlyChf": 6000, "startOffsetMonths": 24},
                    {"type": "add_flag", "flag": "did_master", "startOffsetMonths": 24}]""",
                 "Two years of master's, then a job at CHF {salary}.", "Dos años de máster y después un trabajo de {salary} CHF.", salary(4))},
        ${action("A-start-working", "Start working now", "Empezar a trabajar ya", """{"facts": {"EDUCATION": ["BACHELOR_IN_PROGRESS"]}}""",
                 """[{"type": "set_fact", "key": "EDUCATION", "value": "BACHELOR"}, {"type": "set_employment", "employment": "EMPLOYED"},
                    {"type": "set_fact", "key": "OCCUPATION", "value": "JUNIOR"}, {"type": "set_income", "monthlyChf": 5500},
                    {"type": "add_flag", "flag": "started_working_early"}]""",
                 "You start working at CHF {salary}.", "Empiezas a trabajar con {salary} CHF.", salary(3))},
        ${action("A-shared-flat", "Move into a shared flat", "Irte a un piso compartido", """{"household": ["SINGLE"]}""",
                 """[{"type": "set_household", "household": "SHARED_HOUSING"}, {"type": "set_fact", "key": "HOUSING", "value": "SHARED_FLAT"},
                    {"type": "set_fact", "key": "FAMILY", "value": "SINGLE"}, {"type": "market_rent", "quantile": 0.5},
                    {"type": "add_flag", "flag": "moved_to_shared_flat"}]""",
                 "You share a flat.", "Compartes piso.")},
        ${action("A-loan", "Take out a personal loan", "Pedir un préstamo personal", """{"maxCashChf": 0}""",
                 """[{"type": "add_liability", "principalChf": 15000, "annualRate": 0.06, "termMonths": 60}, {"type": "add_flag", "flag": "took_loan"}]""",
                 "The bank lends you money.", "El banco te presta dinero.")}],
        "shocks": [{"id": "S-job-loss", "category": "Fake", "title": {"EN": "You lose your job", "ES": "Pierdes tu trabajo"},
                    "description": {"EN": "Your company cuts jobs.", "ES": "Tu empresa recorta plantilla."},
                    "requires": {"employment": ["EMPLOYED"]}, "probability": 0.04, "sources": ["$FAKE_SOURCE"],
                    "effects": [{"type": "set_employment", "employment": "UNEMPLOYED"}, {"type": "income_factor", "factor": 0.1, "months": 6},
                                {"type": "set_employment", "employment": "EMPLOYED", "startOffsetMonths": 6}, {"type": "add_flag", "flag": "lost_job"}],
                    "outcome": {"EN": "Six months without a job.", "ES": "Seis meses sin trabajo."}}]}""")

    private fun flow(key: String, direction: String, basis: String, category: String?, months: List<Int>, p: Double, a: Double,
                     aFactors: String = "{}", pFactors: String = "{}") = """
        {"key": "$key", "datasetCategory": "$key", "direction": "$direction", "basis": "$basis",
         "expenseCategory": ${category?.let { "\"$it\"" } ?: "null"}, "months": $months,
         "probability": {"base": $p, "factors": $pFactors},
         "amount": {"base": $a, "factors": $aFactors, "residualDeciles": [0.5,0.6,0.7,0.8,0.9,1.0,1.1,1.2,1.3,1.4,1.5],
                    "residualMeanSquare": 1.05, "personLevelSd": 0.0}, "nProfiles": 1, "nOccurrences": 1}"""

    private val ALL_MONTHS = (1..12).toList()

    /** Salary, rent, living costs (cheaper for students), tuition while studying and a December bonus. */
    val flowModel: FlowModel = json.decodeFromString(
        """{"meta": {"generator": "FakeData", "version": 1, "calibratedRange": "fake", "features": [], "numericFeatures": {}, "bandEdges": {}},
            "cityCanton": {"Lausanne": "VD", "Geneva": "GE", "Zurich": "ZH", "Bern": "BE", "Fribourg": "FR"},
            "rentModel": {"base": 1500.0, "factors": {"household": {"SINGLE": 1.0, "SHARED_HOUSING": 0.68}},
                          "residualDeciles": [0.5,0.6,0.7,0.8,0.9,1.0,1.1,1.2,1.3,1.4,1.5], "nProfiles": 1},
            "flows": [${flow("salary", "IN", "INCOME", null, ALL_MONTHS, 1.0, 1.0)},
                      ${flow("rent", "OUT", "RENT", "RENT", ALL_MONTHS, 1.0, 1.0)},
                      ${flow("groceries", "OUT", "CHF", "LIVING", ALL_MONTHS, 1.0, 1300.0, aFactors = """{"studying": {"NO": 1.0, "YES": 0.8}}""")},
                      ${flow("tuition", "OUT", "CHF", "TUITION", listOf(2, 9), 1.0, 800.0, pFactors = """{"studying": {"NO": 0.0, "YES": 1.0}}""")},
                      ${flow("bonus", "IN", "INCOME", null, listOf(12), 1.0, 0.5, pFactors = """{"employment": {"EMPLOYED": 1.0}}""")}]}""")

    /** The real engine on [flowModel] and [events], without macro trends: for previews and UI tests. */
    fun engine(): RealGameEngine = RealGameEngine(
        TwinEngine(Simulator(flowModel, null, EngineRules(assumptions.financingRateAnnual, assumptions.investmentReturnAnnual)),
                   events.actions, events.shocks))

    // ---------- Interview (P1) ----------

    private fun q(id: String, vararg options: Pair<String, Map<ProfileField, String>>) =
        InterviewQuestion(id, "q_${id}", options.map { (optId, sets) -> InterviewOption(optId, "q_${id}_$optId", sets) })

    /** A typed number (with "I don't know" when [dontKnow]). */
    private fun n(id: String, field: ProfileField, min: Double, max: Double, step: Double, unit: InputUnit, typical: Double, dontKnow: Boolean = true) =
        InterviewQuestion(id, "q_$id", if (dontKnow) listOf(InterviewOption("dk", "q_${id}_dk", emptyMap())) else emptyList(),
                          NumberInput(field, min, max, step, unit, typical))

    val interview: List<InterviewQuestion> = listOf(
        n("age", ProfileField.AGE, 18.0, 76.0, 1.0, InputUnit.YEARS, 46.0, dontKnow = false),
        q("city",
            "lausanne" to mapOf(ProfileField.CITY to "Lausanne"),
            "geneva" to mapOf(ProfileField.CITY to "Geneva"),
            "zurich" to mapOf(ProfileField.CITY to "Zurich"),
            "bern" to mapOf(ProfileField.CITY to "Bern")),
        // Work and studies are independent: a working student is PART_TIME + MASTER_IN_PROGRESS (docs 5.1, #15).
        q("employment",
            "not_working" to mapOf(ProfileField.EMPLOYMENT to "NOT_WORKING"),
            "employed" to mapOf(ProfileField.EMPLOYMENT to "EMPLOYED"),
            "part_time" to mapOf(ProfileField.EMPLOYMENT to "PART_TIME"),
            "self_employed" to mapOf(ProfileField.EMPLOYMENT to "SELF_EMPLOYED")),
        // Codes of content/facts.json (FactKey.EDUCATION).
        q("education",
            "bachelor_in_progress" to mapOf(ProfileField.EDUCATION to "BACHELOR_IN_PROGRESS"),
            "master_in_progress" to mapOf(ProfileField.EDUCATION to "MASTER_IN_PROGRESS"),
            "bachelor" to mapOf(ProfileField.EDUCATION to "BACHELOR"),
            "master" to mapOf(ProfileField.EDUCATION to "MASTER"),
            "none" to mapOf(ProfileField.EDUCATION to "UNKNOWN")),
        q("household",
            "single" to mapOf(ProfileField.HOUSEHOLD to "SINGLE"),
            "couple" to mapOf(ProfileField.HOUSEHOLD to "COUPLE"),
            "family" to mapOf(ProfileField.HOUSEHOLD to "FAMILY"),
            "shared" to mapOf(ProfileField.HOUSEHOLD to "SHARED_HOUSING")),
        n("income", ProfileField.MONTHLY_INCOME, 0.0, 12_300.0, 50.0, InputUnit.CHF_MONTH, 5_550.0),
        n("rent", ProfileField.MONTHLY_RENT, 0.0, 3_950.0, 50.0, InputUnit.CHF_MONTH, 1_950.0),
        n("cash", ProfileField.OPENING_CASH, 0.0, 150_000.0, 500.0, InputUnit.CHF, 53_000.0),
        n("investments", ProfileField.INVESTMENTS, 0.0, 163_500.0, 500.0, InputUnit.CHF, 32_000.0),
        n("debt", ProfileField.DEBT, 0.0, 65_000.0, 500.0, InputUnit.CHF, 0.0),
        q("goal",
            "emergency" to mapOf(ProfileField.GOAL_TYPE to "EMERGENCY"),
            "home" to mapOf(ProfileField.GOAL_TYPE to "HOME"),
            "travel" to mapOf(ProfileField.GOAL_TYPE to "TRAVEL"),
            "retirement" to mapOf(ProfileField.GOAL_TYPE to "RETIREMENT")),
        q("objective",
            "growth" to mapOf(ProfileField.OBJECTIVE to "GROWTH"),
            "balanced" to mapOf(ProfileField.OBJECTIVE to "BALANCED"),
            "safe" to mapOf(ProfileField.OBJECTIVE to "SAFE")),
        n("mincash", ProfileField.MIN_CASH, 0.0, 42_000.0, 500.0, InputUnit.CHF, 3_500.0, dontKnow = false),
    )
}
