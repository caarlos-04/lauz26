package com.lauzhack.twin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Root of content/events.json.
@Serializable
data class EventCatalog(
    val version: Int,
    val events: List<Event>,
)

@Serializable
data class Event(
    val id: String,
    val category: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val requires: Requirements? = null,
    val priority: Int = 0,
    val repeatable: Boolean = false,
    val earliestTurn: Int = 0,
    val sources: List<String>,
    val options: List<EventOption>,         // exactly 2: "A" and "B"
)

// All present fields must match at the start of the turn.
@Serializable
data class Requirements(
    val employment: List<Employment>? = null,
    val household: List<Household>? = null,
    val minAge: Int? = null,
    val maxAge: Int? = null,
    val facts: Map<FactKey, List<String>>? = null,
    val flagsAll: List<String>? = null,
    val flagsNone: List<String>? = null,
    val minCashChf: Double? = null,
    val maxCashChf: Double? = null,
)

@Serializable
data class EventOption(
    val id: String,
    val label: LocalizedText,
    val cases: Map<CaseType, CaseOutcome>,  // all 3 cases
)

@Serializable
data class CaseOutcome(
    val prob: Double,
    val effects: List<Effect>,
    val narrative: LocalizedText,
)

// JSON discriminator: "type". Offsets are months from the start of the turn.
@Serializable
sealed interface Effect {
    @Serializable
    @SerialName("set_income")
    data class SetIncome(val monthlyChf: Double, val startOffsetMonths: Int = 0) : Effect

    @Serializable
    @SerialName("income_delta")
    data class IncomeDelta(
        val deltaChf: Double,
        val startOffsetMonths: Int = 0,
        val months: Int? = null,
    ) : Effect

    // Multiplies the income (e.g. part time 0.557, from the dataset); with `months` the factor is undone at the end.
    @Serializable
    @SerialName("income_factor")
    data class IncomeFactor(val factor: Double, val startOffsetMonths: Int = 0, val months: Int? = null) : Effect

    // Changes the household (rent model input). Must come with the HOUSING / FAMILY facts it implies (validator).
    @Serializable
    @SerialName("set_household")
    data class SetHousehold(val household: Household, val startOffsetMonths: Int = 0) : Effect

    // A new home: the rent becomes the market rent for the person's city and household (FlowModel.rentModel) at this
    // quantile of the dataset spread (0.25 = a cheap flat, 0.75 = an expensive one). Put it after the city/household change.
    @Serializable
    @SerialName("market_rent")
    data class MarketRent(val quantile: Double = 0.5, val startOffsetMonths: Int = 0) : Effect

    @Serializable
    @SerialName("expense_delta")
    data class ExpenseDelta(
        val category: ExpenseCategory,
        val deltaChf: Double,
        val startOffsetMonths: Int = 0,
        val months: Int? = null,
    ) : Effect

    @Serializable
    @SerialName("one_off")
    data class OneOff(val chf: Double, val startOffsetMonths: Int = 0) : Effect

    @Serializable
    @SerialName("add_liability")          // a personal loan paid out to cash, repaid as an annuity over termMonths
    data class AddLiability(
        val principalChf: Double,
        val annualRate: Double,
        val termMonths: Int,
    ) : Effect

    @Serializable
    @SerialName("set_employment")
    data class SetEmployment(val employment: Employment, val startOffsetMonths: Int = 0) : Effect

    @Serializable
    @SerialName("set_fact")
    data class SetFact(val key: FactKey, val value: String, val startOffsetMonths: Int = 0) : Effect

    @Serializable
    @SerialName("add_flag")
    data class AddFlag(val flag: String, val startOffsetMonths: Int = 0) : Effect
}
