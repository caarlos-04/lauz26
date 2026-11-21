package com.lauzhack.twin.model

import kotlinx.serialization.Serializable

// Inputs of the simulator (docs/simulator.md). Both files are generated offline by tools/ and loaded as they are.
//   flow-model.json  (tools/flow_model.py)  what comes in and goes out each month, learned from the dataset
//   macro.json       (tools/macro.py)       yearly growth of prices, wages and rents, from public Swiss data
// Decode with Json { ignoreUnknownKeys = true }: the files also carry reports and series the engine does not need.

// ------------------------------------------------------------------------------------------------ flow model

// Root of flow-model.json.
@Serializable
data class FlowModel(
    val meta: FlowModelMeta,
    val cityCanton: Map<String, String>,    // "Lausanne" -> "VD"; a city not listed has canton null (e.g. abroad)
    val flows: List<FlowSpec>,
)

@Serializable
data class FlowModelMeta(
    val generator: String,
    val version: Int,
    val calibratedRange: String,            // "2025-01..2026-12"
    val features: List<String>,             // FlowFeature keys, in the order the selection tried them
    val numericFeatures: Map<String, String>,       // band feature -> numeric field: "cashBand" -> "cash"
    val bandEdges: Map<String, List<Double>>,       // numeric field -> sorted edges; labels "<e1", "e1-e2", ..., "ek+"
    val caveats: List<String> = emptyList(),
)

// One flow of the monthly cash flow, e.g. groceries or the December bonus. In a month listed in `months`:
//   expected amount = probability(x) * amount(x) * basis       (signed by `direction`)
//   probability(x) = probability.base * product of probability.factors[feature][x.feature]   (capped at 1)
//   amount(x)      = amount.base      * product of amount.factors[feature][x.feature]
// A level that is not in a factor map (e.g. city ABROAD) uses unknown[feature]: the national average.
// Only the features the data support appear in `factors`; `selection` says why every other feature was left out.
@Serializable
data class FlowSpec(
    val key: String,                        // "salary", "bonus", "rent", "groceries", "tuition", "travel"...
    val datasetCategory: String,            // category in synthetic-transactions.csv
    val direction: FlowDirection,
    val expenseCategory: ExpenseCategory?,  // null for income flows
    val basis: FlowBasis,
    val months: List<Int>,                  // calendar months 1..12 when the flow can happen
    val probability: ProbabilityModel,
    val amount: AmountModel,
    val nProfiles: Int,
    val nOccurrences: Int,
)

@Serializable
enum class FlowDirection { IN, OUT }

// What the learned amount multiplies. INCOME = sum of the active income streams; RENT = the active RENT streams.
@Serializable
enum class FlowBasis { CHF, INCOME, RENT }

// Every field that describes the person is a candidate feature, categorical and numeric; `key` is the name used in
// the factor maps (same order as FlowModelMeta.features). Categorical: studying "YES"/"NO" (facts.EDUCATION ends in
// _IN_PROGRESS), employment = Employment name, city = facts.CITY, canton = cityCanton[city], household = Household
// name, education / occupation / housing / family = the facts codes. Numeric, as band labels from
// FlowModelMeta.bandEdges, read at the start of the month: age = person.age, income = monthlyIncomeChf, rent =
// monthlyRentChf, cash = cashChf, debt = loan.principalChf (0 if none), investments = investmentsChf.
// Missing values: "UNKNOWN".
enum class FlowFeature(val key: String) {
    STUDYING("studying"), EMPLOYMENT("employment"), CANTON("canton"), HOUSEHOLD("household"),
    AGE_BAND("ageBand"), INCOME_BAND("incomeBand"), RENT_BAND("rentBand"), CASH_BAND("cashBand"),
    DEBT_BAND("debtBand"), INVESTMENTS_BAND("investmentsBand"), CITY("city"), EDUCATION("education"),
    OCCUPATION("occupation"), HOUSING("housing"), FAMILY("family"),
}

@Serializable
data class ProbabilityModel(
    val base: Double,                                   // probability for the reference person
    val reference: Map<String, String> = emptyMap(),    // feature -> reference level (factor 1)
    val factors: Map<String, Map<String, Double>> = emptyMap(),
    val unknown: Map<String, Double> = emptyMap(),      // feature -> factor for a level not in `factors`
    val selection: List<FeatureSelection> = emptyList(),
)

@Serializable
data class AmountModel(
    val base: Double,                                   // amount per unit of basis for the reference person
    val reference: Map<String, String> = emptyMap(),
    val factors: Map<String, Map<String, Double>> = emptyMap(),
    val unknown: Map<String, Double> = emptyMap(),
    val selection: List<FeatureSelection> = emptyList(),
    val residualDeciles: List<Double>,      // 11 multipliers (min, p10..p90, max) of one occurrence around amount(x)
    val residualMeanSquare: Double,         // E[multiplier^2]: monthly sd = sqrt(p * a^2 * rms - (p * a)^2)
    val personLevelSd: Double,              // sd of a person's own level around amount(x), fraction of the amount
)

// Why a feature is (not) in the model: shown to explain "what the model learned".
@Serializable
data class FeatureSelection(
    val feature: String,
    val kept: Boolean,
    val pValue: Double,
    val maxEffect: Double,                  // largest |factor - 1| among frequent levels
    val fStat: Double? = null,              // null = perfect fit or not testable
    val note: String? = null,               // e.g. "a single value among the people with this flow"
)

// ------------------------------------------------------------------------------------------------ macro trends

// Root of macro.json. Only the trends are decoded; `series` (the public data behind them) is for the UI and docs.
@Serializable
data class MacroFile(
    val meta: MacroMeta,
    val trends: MacroTrends,
)

@Serializable
data class MacroMeta(
    val generator: String,
    val version: Int,
    val notIncluded: List<String> = emptyList(),
    val caveat: String = "",
)

// Applied every month from the second one, as (1 + yearly rate)^(1/12). Rates are annual fractions (0.01 = 1 %).
@Serializable
data class MacroTrends(
    val priceInflationAnnual: RateTrend,            // learned flows and expense streams with indexation CPI
    val wageGrowthAnnual: RateTrend,                // income streams with indexation WAGES (everyone)
    val rentGrowthSittingTenantAnnual: RateTrend,   // RENT streams while the person does not move
    val careerCurveByAge: CareerCurveTrend,         // WAGES streams, by age and occupation, only while working
    val safeReturnAnnual: RateTrend,                // reference only (conservative investments)
    val jobLossRiskByAge: JobLossTrend,             // for event probabilities, not the monthly step
    val homePriceGrowthAnnual: RateTrend,           // for housing events and goals
)

@Serializable
data class RateTrend(
    val value: Double,
    val low: Double? = null,                // range for sensitivity analysis
    val high: Double? = null,
    val origin: Origin,                     // OBSERVED (from a series) or ASSUMED
    val basis: String = "",                 // how the value was obtained, shown to the user
    val series: String? = null,             // key of the series in macro.json
    val pending: String? = null,            // what would replace an ASSUMED value
)

// Pay each month, while working: pay * (1 + wageGrowthAnnual)^(1/12) * level(age + 1 month) / level(age), on the
// curve of the person's occupation (facts.OCCUPATION -> occupationCurve; defaultCurve if absent).
@Serializable
data class CareerCurveTrend(
    val value: CareerCurves?,               // null = not applied
    val origin: Origin,
    val basis: String = "",
    val series: String? = null,
)

@Serializable
data class CareerCurves(
    val curves: Map<String, CareerCurve>,       // BFS position -> curve: "allPositions", "management", "noManagement"...
    val defaultCurve: String,
    val occupationCurve: Map<String, String>,   // OCCUPATION code -> curve name, e.g. "SENIOR" -> "management" (ASSUMED)
)

@Serializable
data class CareerCurve(
    val points: List<List<Double>>,         // [[age, median CHF], ...] sorted by age; linear in between
    val flatAfterAge: Double,               // level stays constant after this age (the curve's peak)
)

@Serializable
data class JobLossTrend(
    val value: Map<String, Double?>,        // "under25" -> 7.99, "from25to74" -> 3.97 (% unemployment rate)
    val unit: String,
    val origin: Origin,
    val series: String? = null,
)
