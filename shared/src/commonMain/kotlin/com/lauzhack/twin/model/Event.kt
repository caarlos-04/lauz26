package com.lauzhack.twin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Root of content/events.json: the pool of actions the player chooses from, and the involuntary shocks
// (docs/decision-tree.md).
@Serializable
data class ActionCatalog(
    val version: Int,
    val actions: List<Action>,
    val shocks: List<Shock> = emptyList(),
)

/** "Carry on as you are": always in the pool, no requirements, no effects. What is under way carries on. */
const val NOTHING = "NOTHING"

// Something the player can decide at a node of the tree. Its uncertain numbers are drawn at the node's luck (a percentile).
@Serializable
data class Action(
    val id: String,
    val category: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val requires: Requirements? = null,
    val repeatable: Boolean = false,            // false = at most once per life (path of the tree)
    val earliestTurn: Int = 0,
    val sources: List<String>,
    val effects: List<Effect> = emptyList(),    // with the median of every uncertain number
    val uncertain: List<Uncertain> = emptyList(),
    val outcome: LocalizedText,                 // the story of the outcome; {name} = an uncertain number, as drawn
)

// Something that happens to the person whatever they choose (job loss): drawn once per turn with its probability.
@Serializable
data class Shock(
    val id: String,
    val category: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val requires: Requirements? = null,
    val probability: Double,                    // chance per turn of 24 months
    val sources: List<String>,
    val effects: List<Effect> = emptyList(),
    val uncertain: List<Uncertain> = emptyList(),
    val outcome: LocalizedText,
)

/**
 * A number of an outcome that is not known in advance (a starting salary, a tuition, a pay factor, a rate, a duration):
 * a two-piece normal (on the value, or on its logarithm) through three values of the data, [low], [median] and [high].
 * The luck of the decision (0..1) is the percentile drawn; [better] says which side good luck gets.
 */
@Serializable
data class Uncertain(
    val name: String,                           // the placeholder in the outcome text
    val law: Law,
    val median: Double,
    val low: Quantile,                          // e.g. p25 of the data
    val high: Quantile,                         // e.g. p75
    val better: Better,
    val step: Double,                           // the drawn value is rounded to it
    val format: String,                         // how the story tells it: chf, change, share, percent, months, flat
    val targets: List<Target>,                  // the effect fields it sets
    val min: Double? = null,
    val max: Double? = null,
    val source: String? = null,
)

@Serializable
data class Quantile(val q: Double, val value: Double)

@Serializable
data class Target(val effect: Int, val field: String)

@Serializable
enum class Law { NORMAL, LOGNORMAL, QUANTILE }

@Serializable
enum class Better { HIGH, LOW }

// All present fields must match at the start of the turn (for a shock: after the action's immediate changes).
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
