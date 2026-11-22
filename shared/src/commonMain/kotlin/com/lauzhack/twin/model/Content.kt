package com.lauzhack.twin.model

import kotlinx.serialization.Serializable

// Root of content/facts.json.
@Serializable
data class FactCatalog(
    val version: Int,
    val facts: Map<FactKey, Map<String, LocalizedText>>,    // key -> code -> label
    val flags: Map<String, LocalizedText>,
    val initialFromProfile: Map<FactKey, InitialFactRule>,
)

// How a fact is derived from the profile: "from" is "datasetEmployment" (the dataset's employment_status, which also
// encodes studying), "household" or "city".
@Serializable
data class InitialFactRule(
    val from: String,
    val map: Map<String, String> = emptyMap(),
    val default: String? = null,
)

// Root of content/assumptions.json: engine rules and value judgements that do NOT come from data.
// Growth of prices, wages and rents comes from data: see MacroTrends (macro.json).
@Serializable
data class Assumptions(
    val investmentReturnAnnual: Double,
    val financingRateAnnual: Double,
    val objectivePenalties: Map<Objective, Double>,
    val sensitivity: List<SensitivityParam>,
    val sourceNotes: List<String>,
)

@Serializable
data class SensitivityParam(
    val id: String,
    val label: LocalizedText,
    val base: Double,
    val low: Double,
    val high: Double,
)

@Serializable
data class InterviewQuestion(
    val id: String,
    val textKey: String,
    val options: List<InterviewOption> = emptyList(),   // a choice; for a number, only "I don't know" (if any)
    val input: NumberInput? = null,                     // a number the person types (age, income, debt...)
)

// A typed number: it fills [field] and must be between [min] and [max] (the dataset range); [typical] (the dataset
// median) is shown as a hint and pre-filled.
@Serializable
data class NumberInput(
    val field: ProfileField,
    val min: Double,
    val max: Double,
    val step: Double,
    val unit: InputUnit,
    val typical: Double,
)

@Serializable
enum class InputUnit { YEARS, CHF, CHF_MONTH }

@Serializable
data class InterviewOption(
    val id: String,
    val labelKey: String,
    val sets: Map<ProfileField, String>,
)

// One answer: an option of a choice (or "I don't know"), or a typed number.
@Serializable
data class InterviewAnswer(
    val questionId: String,
    val optionId: String? = null,
    val value: Double? = null,
)

@Serializable
data class ChatMessage(
    val role: ChatRole,
    val text: String,
)
