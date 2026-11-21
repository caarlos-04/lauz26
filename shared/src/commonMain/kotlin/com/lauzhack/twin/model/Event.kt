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
    data class SetIncome(val streamId: String, val monthlyChf: Double, val startOffsetMonths: Int = 0) : Effect

    @Serializable
    @SerialName("income_delta")
    data class IncomeDelta(
        val streamId: String,
        val deltaChf: Double,
        val startOffsetMonths: Int = 0,
        val months: Int? = null,
    ) : Effect

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
    @SerialName("add_liability")
    data class AddLiability(
        val liabilityType: LiabilityType,
        val principalChf: Double,
        val annualRate: Double,
        val termMonths: Int,
        val disburseToCash: Boolean = true,
    ) : Effect

    @Serializable
    @SerialName("asset_return_delta")
    data class AssetReturnDelta(val assetType: AssetType, val deltaAnnualRate: Double) : Effect

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
