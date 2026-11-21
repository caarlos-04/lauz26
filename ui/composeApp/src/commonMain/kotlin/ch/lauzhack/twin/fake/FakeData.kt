package ch.lauzhack.twin.fake

import ch.lauzhack.twin.model.Asset
import ch.lauzhack.twin.model.AssetType
import ch.lauzhack.twin.model.Assumptions
import ch.lauzhack.twin.model.CaseOutcome
import ch.lauzhack.twin.model.CaseType
import ch.lauzhack.twin.model.Effect
import ch.lauzhack.twin.model.Employment
import ch.lauzhack.twin.model.Event
import ch.lauzhack.twin.model.EventOption
import ch.lauzhack.twin.model.ExpenseCategory
import ch.lauzhack.twin.model.ExpenseStream
import ch.lauzhack.twin.model.FinancialState
import ch.lauzhack.twin.model.Goal
import ch.lauzhack.twin.model.Household
import ch.lauzhack.twin.model.IncomeStream
import ch.lauzhack.twin.model.InterviewOption
import ch.lauzhack.twin.model.InterviewQuestion
import ch.lauzhack.twin.model.Lang
import ch.lauzhack.twin.model.Liability
import ch.lauzhack.twin.model.LiabilityType
import ch.lauzhack.twin.model.Priority
import ch.lauzhack.twin.model.Requirements
import ch.lauzhack.twin.model.ProfileField
import ch.lauzhack.twin.model.UserProfile

/**
 * Placeholder data so the UI runs before Dev C ships the JSON files.
 * ALL values are synthetic and illustrative (CHF, rough Swiss orders of magnitude), not calibrated.
 */
object FakeData {

    // ---------- Assumptions ----------

    val assumptions = Assumptions(
        inflationAnnual = 0.01,
        salaryGrowthAnnual = 0.015,
        investmentReturn = mapOf(
            CaseType.PESSIMISTIC to -0.02,
            CaseType.NEUTRAL to 0.04,
            CaseType.OPTIMISTIC to 0.07,
        ),
        defaultCaseProbs = mapOf(
            CaseType.PESSIMISTIC to 0.2,
            CaseType.NEUTRAL to 0.6,
            CaseType.OPTIMISTIC to 0.2,
        ),
        // Shown verbatim in the UI; Dev C will replace them with the real sources.
        sourceNotes = listOf(
            "Inflation 1 %/yr, salary growth 1.5 %/yr: placeholder assumptions (not calibrated).",
            "Investment returns -2 % / 4 % / 7 % per case: illustrative values.",
            "Case probabilities 20 % / 60 % / 20 % by default.",
            "Months 0-24 will be calibrated with the synthetic dataset; months 24-120 rely on assumptions only.",
            "No taxes are modelled.",
        ),
    )

    // ---------- Alexia (demo profile) ----------

    val alexiaProfile = UserProfile(
        id = "DEMO-ALEXIA",
        displayName = "Alexia",
        ageAtStart = 20,
        city = "Lausanne",
        canton = "VD",
        employment = Employment.STUDENT,
        household = Household.SHARED_HOUSING,
        startMonth = "2025-01",
        goals = listOf(
            Goal("G-ALEXIA-01", "goal_emergency", 10_000.0, 36, Priority.HIGH),
            Goal("G-ALEXIA-02", "goal_travel", 6_000.0, 60, Priority.LOW),
            Goal("G-ALEXIA-03", "goal_home", 40_000.0, 108, Priority.MEDIUM),
        ),
    )

    val alexiaState = FinancialState(
        month = 0,
        cash = 8_000.0,
        assets = listOf(
            Asset("savings-3a", AssetType.INVESTMENT_PORTFOLIO, 2_000.0, 0.01),
        ),
        liabilities = emptyList(),
        income = listOf(
            IncomeStream("job", "Part-time job", 1_500.0),
            IncomeStream("family", "Family support", 500.0),
        ),
        expenses = listOf(
            ExpenseStream("rent", ExpenseCategory.RENT, 800.0),
            ExpenseStream("living", ExpenseCategory.LIVING, 700.0),
            ExpenseStream("insurance", ExpenseCategory.INSURANCE, 280.0),
            ExpenseStream("tuition", ExpenseCategory.TUITION, 120.0),
        ),
        employment = Employment.STUDENT,
    )

    // ---------- Event catalog ----------

    private fun outcome(prob: Double, en: String, es: String, vararg effects: Effect) =
        CaseOutcome(prob, effects.toList(), mapOf(Lang.EN to en, Lang.ES to es))

    private fun cases(p: CaseOutcome, n: CaseOutcome, o: CaseOutcome) =
        mapOf(CaseType.PESSIMISTIC to p, CaseType.NEUTRAL to n, CaseType.OPTIMISTIC to o)

    val events: List<Event> = listOf(
        Event(
            id = "E-studies",
            titleKey = "event_studies_title",
            descriptionKey = "event_studies_desc",
            options = listOf(
                EventOption(
                    "A", "event_studies_a",
                    cases(
                        outcome(0.2, "You need an extra semester and pay for books and fees.",
                            "Necesitas un semestre extra y pagas libros y tasas.",
                            Effect.ExpenseDelta(ExpenseCategory.TUITION, 150.0, 24),
                            Effect.IncomeDelta("job", -300.0, 24),
                            Effect.OneOffCost(1_500.0)),
                        outcome(0.6, "The master goes as planned; you work fewer hours.",
                            "El máster va según lo previsto; trabajas menos horas.",
                            Effect.ExpenseDelta(ExpenseCategory.TUITION, 150.0, 24),
                            Effect.IncomeDelta("job", -300.0, 24)),
                        outcome(0.2, "You win a scholarship that covers part of your costs.",
                            "Consigues una beca que cubre parte de tus gastos.",
                            Effect.ExpenseDelta(ExpenseCategory.TUITION, 150.0, 24),
                            Effect.IncomeDelta("scholarship", 500.0, 24)),
                    ),
                ),
                EventOption(
                    "B", "event_studies_b",
                    cases(
                        outcome(0.2, "Junior job with a modest salary.",
                            "Trabajo junior con un sueldo modesto.",
                            Effect.SetEmployment(Employment.EMPLOYED),
                            Effect.IncomeDelta("job", -1_500.0, null),
                            Effect.IncomeDelta("family", -500.0, null),
                            Effect.IncomeDelta("salary", 3_600.0, null),
                            Effect.ExpenseDelta(ExpenseCategory.TUITION, -120.0, null)),
                        outcome(0.6, "You find a full-time job in Lausanne.",
                            "Encuentras un trabajo a tiempo completo en Lausana.",
                            Effect.SetEmployment(Employment.EMPLOYED),
                            Effect.IncomeDelta("job", -1_500.0, null),
                            Effect.IncomeDelta("family", -500.0, null),
                            Effect.IncomeDelta("salary", 4_400.0, null),
                            Effect.ExpenseDelta(ExpenseCategory.TUITION, -120.0, null)),
                        outcome(0.2, "A start-up hires you with a great salary.",
                            "Una start-up te contrata con un buen sueldo.",
                            Effect.SetEmployment(Employment.EMPLOYED),
                            Effect.IncomeDelta("job", -1_500.0, null),
                            Effect.IncomeDelta("family", -500.0, null),
                            Effect.IncomeDelta("salary", 5_200.0, null),
                            Effect.ExpenseDelta(ExpenseCategory.TUITION, -120.0, null)),
                    ),
                ),
            ),
        ),
        Event(
            id = "E-housing",
            titleKey = "event_housing_title",
            descriptionKey = "event_housing_desc",
            earliestTurn = 1,
            options = listOf(
                EventOption(
                    "A", "event_housing_a",
                    cases(
                        outcome(0.2, "Only an expensive studio is free; furniture costs more than expected.",
                            "Solo queda un estudio caro; los muebles cuestan más de lo previsto.",
                            Effect.ExpenseDelta(ExpenseCategory.RENT, 650.0, null), Effect.OneOffCost(3_000.0)),
                        outcome(0.6, "You rent a small studio near the lake.",
                            "Alquilas un pequeño estudio cerca del lago.",
                            Effect.ExpenseDelta(ExpenseCategory.RENT, 500.0, null), Effect.OneOffCost(2_000.0)),
                        outcome(0.2, "A friend sublets you a cheap studio, already furnished.",
                            "Un amigo te subalquila un estudio barato y amueblado.",
                            Effect.ExpenseDelta(ExpenseCategory.RENT, 300.0, null), Effect.OneOffCost(500.0)),
                    ),
                ),
                EventOption(
                    "B", "event_housing_b",
                    cases(
                        outcome(0.2, "A flatmate leaves and you cover their share for a year.",
                            "Un compañero se va y pagas su parte durante un año.",
                            Effect.ExpenseDelta(ExpenseCategory.RENT, 250.0, 12)),
                        outcome(0.6, "Nothing changes; shared flat life goes on.",
                            "Nada cambia; la vida en el piso compartido sigue.",
                        ),
                        outcome(0.2, "A new flatmate joins and the rent per person drops.",
                            "Entra un compañero nuevo y el alquiler por persona baja.",
                            Effect.ExpenseDelta(ExpenseCategory.RENT, -120.0, null)),
                    ),
                ),
            ),
        ),
        Event(
            id = "E-invest",
            titleKey = "event_invest_title",
            descriptionKey = "event_invest_desc",
            earliestTurn = 2,
            options = listOf(
                EventOption(
                    "A", "event_invest_a",
                    cases(
                        outcome(0.2, "Markets fall: your portfolio loses value for years.",
                            "Los mercados caen: tu cartera pierde valor durante años.",
                            Effect.AssetReturnDelta(AssetType.INVESTMENT_PORTFOLIO, -0.03)),
                        outcome(0.6, "Markets grow at an average pace.",
                            "Los mercados crecen a un ritmo medio.",
                            Effect.AssetReturnDelta(AssetType.INVESTMENT_PORTFOLIO, 0.03)),
                        outcome(0.2, "A bull market boosts your portfolio.",
                            "Un mercado alcista impulsa tu cartera.",
                            Effect.AssetReturnDelta(AssetType.INVESTMENT_PORTFOLIO, 0.06)),
                    ),
                ),
                EventOption(
                    "B", "event_invest_b",
                    cases(
                        outcome(0.2, "Interest rates drop to almost zero.",
                            "Los tipos de interés bajan casi a cero.",
                            Effect.AssetReturnDelta(AssetType.INVESTMENT_PORTFOLIO, -0.005)),
                        outcome(0.6, "Your savings earn a small, stable interest.",
                            "Tus ahorros dan un interés pequeño y estable.",
                        ),
                        outcome(0.2, "Rates go up slightly.",
                            "Los tipos suben ligeramente.",
                            Effect.AssetReturnDelta(AssetType.INVESTMENT_PORTFOLIO, 0.005)),
                    ),
                ),
            ),
        ),
        Event(
            id = "E-car",
            titleKey = "event_car_title",
            descriptionKey = "event_car_desc",
            earliestTurn = 3,
            options = listOf(
                EventOption(
                    "A", "event_car_a",
                    cases(
                        outcome(0.2, "The used car needs a big repair in the first year.",
                            "El coche de segunda mano necesita una gran reparación el primer año.",
                            Effect.AddLiability(Liability("car-loan", LiabilityType.PERSONAL_LOAN, 15_000.0, 0.06, 300.0)),
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 250.0, null),
                            Effect.OneOffCost(2_500.0)),
                        outcome(0.6, "You buy a car on credit; insurance and fuel add up.",
                            "Compras un coche a crédito; seguro y gasolina suman.",
                            Effect.AddLiability(Liability("car-loan", LiabilityType.PERSONAL_LOAN, 15_000.0, 0.06, 300.0)),
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 250.0, null)),
                        outcome(0.2, "The car lets you take a better-paid job outside the city.",
                            "El coche te permite un trabajo mejor pagado fuera de la ciudad.",
                            Effect.AddLiability(Liability("car-loan", LiabilityType.PERSONAL_LOAN, 15_000.0, 0.06, 300.0)),
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 250.0, null),
                            Effect.IncomeDelta("bonus-commute", 400.0, null)),
                    ),
                ),
                EventOption(
                    "B", "event_car_b",
                    cases(
                        outcome(0.2, "Travelcard prices go up.",
                            "Sube el precio del abono.",
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 330.0, null)),
                        outcome(0.6, "You buy a yearly travelcard.",
                            "Compras un abono anual.",
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 240.0, null)),
                        outcome(0.2, "Your employer pays half of the travelcard.",
                            "Tu empresa paga la mitad del abono.",
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 120.0, null)),
                    ),
                ),
            ),
        ),
        // Only makes sense if the player has a debt (e.g. bought the car with a loan).
        Event(
            id = "E-car-loan",
            titleKey = "event_carloan_title",
            descriptionKey = "event_carloan_desc",
            requires = Requirements(requiresLiability = true),
            earliestTurn = 4,
            options = listOf(
                EventOption(
                    "A", "event_carloan_a",
                    cases(
                        outcome(0.2, "The bank charges high fees to refinance.",
                            "El banco cobra comisiones altas por refinanciar.",
                            Effect.OneOffCost(1_200.0)),
                        outcome(0.6, "You refinance and save a bit every month.",
                            "Refinancias y ahorras un poco cada mes.",
                            Effect.OneOffCost(400.0), Effect.ExpenseDelta(ExpenseCategory.OTHER, -60.0, null)),
                        outcome(0.2, "You get a much better rate.",
                            "Consigues un tipo mucho mejor.",
                            Effect.OneOffCost(300.0), Effect.ExpenseDelta(ExpenseCategory.OTHER, -120.0, null)),
                    ),
                ),
                EventOption(
                    "B", "event_carloan_b",
                    cases(
                        outcome(0.2, "The rate keeps rising and the loan gets more expensive.",
                            "El tipo sigue subiendo y el préstamo se encarece.",
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 120.0, null)),
                        outcome(0.6, "You keep paying; costs go up slightly.",
                            "Sigues pagando; los costes suben un poco.",
                            Effect.ExpenseDelta(ExpenseCategory.OTHER, 50.0, null)),
                        outcome(0.2, "Rates fall back again.",
                            "Los tipos vuelven a bajar.",
                        ),
                    ),
                ),
            ),
        ),
        Event(
            id = "E-sabbatical",
            titleKey = "event_sabbatical_title",
            descriptionKey = "event_sabbatical_desc",
            earliestTurn = 4,
            options = listOf(
                EventOption(
                    "A", "event_sabbatical_a",
                    cases(
                        outcome(0.2, "The trip costs more and finding work again takes time.",
                            "El viaje cuesta más y volver a encontrar trabajo lleva tiempo.",
                            Effect.OneOffCost(12_000.0), Effect.IncomeDelta("sabbatical-gap", -2_000.0, 8)),
                        outcome(0.6, "Six months travelling; you come back with stories and less money.",
                            "Seis meses de viaje; vuelves con historias y menos dinero.",
                            Effect.OneOffCost(9_000.0), Effect.IncomeDelta("sabbatical-gap", -1_500.0, 6)),
                        outcome(0.2, "You work remotely part of the trip.",
                            "Trabajas en remoto parte del viaje.",
                            Effect.OneOffCost(7_000.0), Effect.IncomeDelta("sabbatical-gap", -500.0, 6)),
                    ),
                ),
                EventOption(
                    "B", "event_sabbatical_b",
                    cases(
                        outcome(0.2, "No bonus this time, but your job is stable.",
                            "Esta vez no hay bonus, pero tu trabajo es estable.",
                        ),
                        outcome(0.6, "You keep working and get a small raise.",
                            "Sigues trabajando y consigues un pequeño aumento.",
                            Effect.IncomeDelta("raise", 300.0, null)),
                        outcome(0.2, "You are promoted.",
                            "Te ascienden.",
                            Effect.IncomeDelta("raise", 700.0, null)),
                    ),
                ),
            ),
        ),
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
        q("employment",
            "student" to mapOf(ProfileField.EMPLOYMENT to "STUDENT"),
            "employed" to mapOf(ProfileField.EMPLOYMENT to "EMPLOYED"),
            "part_time" to mapOf(ProfileField.EMPLOYMENT to "PART_TIME"),
            "self_employed" to mapOf(ProfileField.EMPLOYMENT to "SELF_EMPLOYED")),
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
    )
}
