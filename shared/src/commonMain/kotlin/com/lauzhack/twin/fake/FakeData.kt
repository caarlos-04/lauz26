package com.lauzhack.twin.fake

import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.CaseOutcome
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.EventOption
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Goal
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.model.InterviewOption
import com.lauzhack.twin.model.InterviewQuestion
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.LocalizedText
import com.lauzhack.twin.model.Objective
import com.lauzhack.twin.model.Person
import com.lauzhack.twin.model.Preferences
import com.lauzhack.twin.model.Priority
import com.lauzhack.twin.model.ProfileField
import com.lauzhack.twin.model.Requirements

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
        defaultCaseProbs = mapOf(CaseType.PESSIMISTIC to 0.2, CaseType.NEUTRAL to 0.6, CaseType.OPTIMISTIC to 0.2),
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

    // ---------- Event catalogue ----------

    private fun outcome(prob: Double, en: String, es: String, vararg effects: Effect) =
        CaseOutcome(prob, effects.toList(), t(en, es))

    private fun cases(p: CaseOutcome, n: CaseOutcome, o: CaseOutcome) =
        mapOf(CaseType.PESSIMISTIC to p, CaseType.NEUTRAL to n, CaseType.OPTIMISTIC to o)

    private const val FAKE_SOURCE = "Fake data for the UI (illustrative, not calibrated)."

    val events: List<Event> = listOf(
        Event(
            id = "E-studies",
            category = "Education & skills",
            title = t("After your bachelor", "Después del grado"),
            description = t(
                "You are about to finish your bachelor. Do you keep studying or start working?",
                "Estás a punto de acabar el grado. ¿Sigues estudiando o empiezas a trabajar?",
            ),
            requires = Requirements(facts = mapOf(FactKey.EDUCATION to listOf("BACHELOR_IN_PROGRESS"))),
            sources = listOf(FAKE_SOURCE),
            options = listOf(
                EventOption(
                    "A", t("Do a master's degree", "Hacer un máster"),
                    cases(
                        outcome(0.2, "You need an extra semester and pay for books and fees.",
                            "Necesitas un semestre extra y pagas libros y tasas.",
                            Effect.ExpenseDelta(ExpenseCategory.TUITION, 150.0, months = 24),
                            Effect.IncomeDelta(-300.0, months = 24),
                            Effect.OneOff(1_500.0),
                            Effect.SetFact(FactKey.EDUCATION, "MASTER_IN_PROGRESS"),
                            Effect.SetFact(FactKey.EDUCATION, "MASTER", startOffsetMonths = 24),
                            Effect.AddFlag("did_master", startOffsetMonths = 24)),
                        outcome(0.6, "The master goes as planned; you work fewer hours.",
                            "El máster va según lo previsto; trabajas menos horas.",
                            Effect.ExpenseDelta(ExpenseCategory.TUITION, 150.0, months = 24),
                            Effect.IncomeDelta(-300.0, months = 24),
                            Effect.SetFact(FactKey.EDUCATION, "MASTER_IN_PROGRESS"),
                            Effect.SetFact(FactKey.EDUCATION, "MASTER", startOffsetMonths = 24),
                            Effect.AddFlag("did_master", startOffsetMonths = 24)),
                        outcome(0.2, "You win a scholarship that covers part of your costs.",
                            "Consigues una beca que cubre parte de tus gastos.",
                            Effect.ExpenseDelta(ExpenseCategory.TUITION, 150.0, months = 24),
                            Effect.IncomeDelta(500.0, months = 24),
                            Effect.SetFact(FactKey.EDUCATION, "MASTER_IN_PROGRESS"),
                            Effect.SetFact(FactKey.EDUCATION, "MASTER", startOffsetMonths = 24),
                            Effect.AddFlag("did_master", startOffsetMonths = 24)),
                    ),
                ),
                EventOption(
                    "B", t("Start working now", "Empezar a trabajar ya"),
                    cases(
                        outcome(0.2, "Junior job with a modest salary of CHF 3,600 a month.",
                            "Trabajo junior con un sueldo modesto de 3.600 CHF al mes.",
                            *startWorking(3_600.0)),
                        outcome(0.6, "You find a full-time job in Lausanne at CHF 4,400 a month.",
                            "Encuentras un trabajo a tiempo completo en Lausana por 4.400 CHF al mes.",
                            *startWorking(4_400.0)),
                        outcome(0.2, "A start-up hires you at CHF 5,200 a month.",
                            "Una start-up te contrata por 5.200 CHF al mes.",
                            *startWorking(5_200.0)),
                    ),
                ),
            ),
        ),
        Event(
            id = "E-housing",
            category = "Housing",
            title = t("Where to live", "Dónde vivir"),
            description = t(
                "You want more independence. Do you move to a studio of your own?",
                "Quieres más independencia. ¿Te mudas a un estudio para ti solo/a?",
            ),
            earliestTurn = 1,
            requires = Requirements(facts = mapOf(FactKey.HOUSING to listOf("SHARED_FLAT"))),
            sources = listOf(FAKE_SOURCE),
            options = listOf(
                EventOption(
                    "A", t("Move to my own studio", "Mudarme a mi propio estudio"),
                    cases(
                        outcome(0.2, "Only an expensive studio is free; furniture costs more than expected.",
                            "Solo queda un estudio caro; los muebles cuestan más de lo previsto.",
                            Effect.SetHousehold(Household.SINGLE), Effect.MarketRent(0.75), Effect.OneOff(3_000.0),
                            Effect.SetFact(FactKey.HOUSING, "RENTED_FLAT"), Effect.AddFlag("rented_own_flat")),
                        outcome(0.6, "You rent a small studio near the lake.",
                            "Alquilas un pequeño estudio cerca del lago.",
                            Effect.SetHousehold(Household.SINGLE), Effect.MarketRent(0.5), Effect.OneOff(2_000.0),
                            Effect.SetFact(FactKey.HOUSING, "RENTED_FLAT"), Effect.AddFlag("rented_own_flat")),
                        outcome(0.2, "A friend sublets you a cheap studio, already furnished.",
                            "Un amigo te subalquila un estudio barato y amueblado.",
                            Effect.SetHousehold(Household.SINGLE), Effect.MarketRent(0.25), Effect.OneOff(500.0),
                            Effect.SetFact(FactKey.HOUSING, "RENTED_FLAT"), Effect.AddFlag("rented_own_flat")),
                    ),
                ),
                EventOption(
                    "B", t("Stay in the shared flat", "Quedarme en el piso compartido"),
                    cases(
                        outcome(0.2, "A flatmate leaves and you cover their share for a year.",
                            "Un compañero se va y pagas su parte durante un año.",
                            Effect.ExpenseDelta(ExpenseCategory.RENT, 250.0, months = 12)),
                        outcome(0.6, "Nothing changes; shared flat life goes on.",
                            "Nada cambia; la vida en el piso compartido sigue."),
                        outcome(0.2, "A new flatmate joins and the rent per person drops.",
                            "Entra un compañero nuevo y el alquiler por persona baja.",
                            Effect.ExpenseDelta(ExpenseCategory.RENT, -120.0)),
                    ),
                ),
            ),
        ),
        Event(
            id = "E-hours",
            category = "Career & employment",
            title = t("Fewer hours?", "¿Menos horas?"),
            description = t(
                "Your employer offers to cut your hours to 80 % of your salary. Do you take it?",
                "Tu empresa te ofrece reducir tu jornada al 80 % del sueldo. ¿La aceptas?",
            ),
            earliestTurn = 2,
            requires = Requirements(employment = listOf(Employment.EMPLOYED, Employment.PART_TIME)),
            sources = listOf(FAKE_SOURCE),
            options = listOf(
                EventOption(
                    "A", t("Work 80 %", "Trabajar al 80 %"),
                    cases(
                        outcome(0.2, "Your workload stays the same, for less pay.",
                            "La carga de trabajo es la misma, por menos sueldo.",
                            Effect.IncomeFactor(0.8), Effect.AddFlag("four_day_week")),
                        outcome(0.6, "You earn less but have a free day every week.",
                            "Ganas menos pero tienes un día libre cada semana.",
                            Effect.IncomeFactor(0.8), Effect.AddFlag("four_day_week")),
                        outcome(0.2, "You use the free day for a paid side project.",
                            "Usas el día libre para un proyecto remunerado.",
                            Effect.IncomeFactor(0.8), Effect.IncomeDelta(500.0), Effect.AddFlag("four_day_week")),
                    ),
                ),
                EventOption(
                    "B", t("Keep my hours", "Mantener mi jornada"),
                    cases(
                        outcome(0.2, "Nothing changes.", "Nada cambia."),
                        outcome(0.6, "You get a small raise.", "Consigues un pequeño aumento.",
                            Effect.IncomeDelta(200.0)),
                        outcome(0.2, "You are promoted.", "Te ascienden.",
                            Effect.IncomeDelta(600.0), Effect.SetFact(FactKey.OCCUPATION, "SENIOR"), Effect.AddFlag("promoted")),
                    ),
                ),
            ),
        ),
        Event(
            id = "E-car",
            category = "Mobility",
            title = t("Getting around", "Moverse"),
            description = t(
                "Commuting is becoming tiring. Do you buy a car on credit or stay with public transport?",
                "Los trayectos se hacen pesados. ¿Compras un coche a crédito o sigues en transporte público?",
            ),
            earliestTurn = 3,
            sources = listOf(FAKE_SOURCE),
            options = listOf(
                EventOption(
                    "A", t("Buy a car with a loan", "Comprar un coche con un préstamo"),
                    cases(
                        outcome(0.2, "The used car needs a big repair in the first year.",
                            "El coche de segunda mano necesita una gran reparación el primer año.",
                            *carLoan(), Effect.ExpenseDelta(ExpenseCategory.OTHER, 250.0), Effect.OneOff(2_500.0)),
                        outcome(0.6, "You buy a car on credit; insurance and fuel add up.",
                            "Compras un coche a crédito; seguro y gasolina suman.",
                            *carLoan(), Effect.ExpenseDelta(ExpenseCategory.OTHER, 250.0)),
                        outcome(0.2, "The car lets you take a better-paid job outside the city.",
                            "El coche te permite un trabajo mejor pagado fuera de la ciudad.",
                            *carLoan(), Effect.ExpenseDelta(ExpenseCategory.OTHER, 250.0), Effect.IncomeDelta(400.0)),
                    ),
                ),
                EventOption(
                    "B", t("Yearly travelcard", "Abono anual de transporte"),
                    cases(
                        outcome(0.2, "Travelcard prices go up.", "Sube el precio del abono.",
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 330.0)),
                        outcome(0.6, "You buy a yearly travelcard.", "Compras un abono anual.",
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 240.0)),
                        outcome(0.2, "Your employer pays half of the travelcard.", "Tu empresa paga la mitad del abono.",
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 120.0)),
                    ),
                ),
            ),
        ),
        // Only makes sense if the player took a loan (milestone "took_loan").
        Event(
            id = "E-car-loan",
            category = "Debt",
            title = t("Your car loan", "Tu préstamo del coche"),
            description = t(
                "Interest rates went up and your car loan is getting more expensive. Do you refinance it?",
                "Los tipos de interés han subido y el préstamo del coche sale más caro. ¿Lo refinancias?",
            ),
            requires = Requirements(flagsAll = listOf("took_loan")),
            earliestTurn = 4,
            priority = 5,
            sources = listOf(FAKE_SOURCE),
            options = listOf(
                EventOption(
                    "A", t("Refinance with another bank", "Refinanciar con otro banco"),
                    cases(
                        outcome(0.2, "The bank charges high fees to refinance.", "El banco cobra comisiones altas por refinanciar.",
                            Effect.OneOff(1_200.0)),
                        outcome(0.6, "You refinance and save a bit every month.", "Refinancias y ahorras un poco cada mes.",
                            Effect.OneOff(400.0), Effect.ExpenseDelta(ExpenseCategory.OTHER, -60.0)),
                        outcome(0.2, "You get a much better rate.", "Consigues un tipo mucho mejor.",
                            Effect.OneOff(300.0), Effect.ExpenseDelta(ExpenseCategory.OTHER, -120.0)),
                    ),
                ),
                EventOption(
                    "B", t("Keep paying as it is", "Seguir pagando igual"),
                    cases(
                        outcome(0.2, "The rate keeps rising and the loan gets more expensive.",
                            "El tipo sigue subiendo y el préstamo se encarece.",
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 120.0)),
                        outcome(0.6, "You keep paying; costs go up slightly.", "Sigues pagando; los costes suben un poco.",
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 50.0)),
                        outcome(0.2, "Rates fall back again.", "Los tipos vuelven a bajar."),
                    ),
                ),
            ),
        ),
        Event(
            id = "E-sabbatical",
            category = "Life",
            title = t("A break?", "¿Un descanso?"),
            description = t(
                "You dream of travelling for six months. Do you take a sabbatical?",
                "Sueñas con viajar seis meses. ¿Te tomas un año sabático?",
            ),
            earliestTurn = 4,
            sources = listOf(FAKE_SOURCE),
            options = listOf(
                EventOption(
                    "A", t("Take a 6-month sabbatical", "Tomarme 6 meses sabáticos"),
                    cases(
                        outcome(0.2, "The trip costs more and finding work again takes time.",
                            "El viaje cuesta más y volver a encontrar trabajo lleva tiempo.",
                            Effect.OneOff(12_000.0), Effect.IncomeDelta(-2_000.0, months = 8)),
                        outcome(0.6, "Six months travelling; you come back with stories and less money.",
                            "Seis meses de viaje; vuelves con historias y menos dinero.",
                            Effect.OneOff(9_000.0), Effect.IncomeDelta(-1_500.0, months = 6)),
                        outcome(0.2, "You work remotely part of the trip.", "Trabajas en remoto parte del viaje.",
                            Effect.OneOff(7_000.0), Effect.IncomeDelta(-500.0, months = 6)),
                    ),
                ),
                EventOption(
                    "B", t("Keep working and saving", "Seguir trabajando y ahorrando"),
                    cases(
                        outcome(0.2, "No bonus this time, but your job is stable.", "Esta vez no hay bonus, pero tu trabajo es estable."),
                        outcome(0.6, "You keep working and get a small raise.", "Sigues trabajando y consigues un pequeño aumento.",
                            Effect.IncomeDelta(300.0)),
                        outcome(0.2, "You are promoted.", "Te ascienden.",
                            Effect.IncomeDelta(700.0), Effect.SetFact(FactKey.OCCUPATION, "SENIOR"), Effect.AddFlag("promoted")),
                    ),
                ),
            ),
        ),
    )

    private fun startWorking(salary: Double): Array<Effect> = arrayOf(
        Effect.SetEmployment(Employment.EMPLOYED),
        Effect.SetIncome(salary),
                Effect.ExpenseDelta(ExpenseCategory.TUITION, -120.0),
        Effect.SetFact(FactKey.EDUCATION, "BACHELOR"),
        Effect.SetFact(FactKey.OCCUPATION, "JUNIOR"),
        Effect.AddFlag("started_working_early"),
    )

    private fun carLoan(): Array<Effect> = arrayOf(
        // The loan is paid out to cash and the car is bought with it (a one-off): the car is not an asset.
        Effect.AddLiability(15_000.0, 0.06, termMonths = 60),
        Effect.OneOff(15_000.0),
        Effect.AddFlag("took_loan"),
    )

    // ---------- Interview (P1) ----------

    private fun q(id: String, vararg options: Pair<String, Map<ProfileField, String>>) =
        InterviewQuestion(id, "q_${id}", options.map { (optId, sets) -> InterviewOption(optId, "q_${id}_$optId", sets) })

    val interview: List<InterviewQuestion> = listOf(
        q("age",
            "a1" to mapOf(ProfileField.AGE to "20"),
            "a2" to mapOf(ProfileField.AGE to "27"),
            "a3" to mapOf(ProfileField.AGE to "38"),
            "a4" to mapOf(ProfileField.AGE to "55")),
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
        q("income",
            "i1" to mapOf(ProfileField.MONTHLY_INCOME to "1200"),
            "i2" to mapOf(ProfileField.MONTHLY_INCOME to "3000"),
            "i3" to mapOf(ProfileField.MONTHLY_INCOME to "5500"),
            "i4" to mapOf(ProfileField.MONTHLY_INCOME to "9000")),
        q("rent",
            "r1" to mapOf(ProfileField.MONTHLY_RENT to "700"),
            "r2" to mapOf(ProfileField.MONTHLY_RENT to "1300"),
            "r3" to mapOf(ProfileField.MONTHLY_RENT to "2000"),
            "r4" to mapOf(ProfileField.MONTHLY_RENT to "3000")),
        q("cash",
            "c1" to mapOf(ProfileField.OPENING_CASH to "2000"),
            "c2" to mapOf(ProfileField.OPENING_CASH to "8000"),
            "c3" to mapOf(ProfileField.OPENING_CASH to "25000"),
            "c4" to mapOf(ProfileField.OPENING_CASH to "80000")),
        q("goal",
            "emergency" to mapOf(ProfileField.GOAL_TYPE to "EMERGENCY"),
            "home" to mapOf(ProfileField.GOAL_TYPE to "HOME"),
            "travel" to mapOf(ProfileField.GOAL_TYPE to "TRAVEL"),
            "retirement" to mapOf(ProfileField.GOAL_TYPE to "RETIREMENT")),
        q("objective",
            "growth" to mapOf(ProfileField.OBJECTIVE to "GROWTH"),
            "balanced" to mapOf(ProfileField.OBJECTIVE to "BALANCED"),
            "safe" to mapOf(ProfileField.OBJECTIVE to "SAFE")),
        q("mincash",
            "m1" to mapOf(ProfileField.MIN_CASH to "1000"),
            "m2" to mapOf(ProfileField.MIN_CASH to "3000"),
            "m3" to mapOf(ProfileField.MIN_CASH to "6000"),
            "m4" to mapOf(ProfileField.MIN_CASH to "12000")),
    )
}
