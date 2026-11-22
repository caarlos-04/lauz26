package com.lauzhack.twin.model

import kotlinx.serialization.Serializable

// How the person earns money. Studying is independent: see FactKey.EDUCATION (a value ending in _IN_PROGRESS).
// A student without a job is NOT_WORKING; a working student is PART_TIME (or EMPLOYED) with EDUCATION in progress.
@Serializable
enum class Employment { NOT_WORKING, EMPLOYED, PART_TIME, SELF_EMPLOYED, UNEMPLOYED, RETIRED }

@Serializable
enum class Household { SINGLE, COUPLE, FAMILY, SHARED_HOUSING }

@Serializable
enum class ExpenseCategory { RENT, LIVING, TUITION, INSURANCE, TRAVEL, OTHER }

@Serializable
enum class FactKey { EDUCATION, OCCUPATION, CITY, HOUSING, FAMILY }

@Serializable
enum class Priority { HIGH, MEDIUM }

// Where a trend comes from (macro.json): public data or our assumption.
@Serializable
enum class Origin { OBSERVED, ASSUMED }

@Serializable
enum class Objective { GROWTH, BALANCED, SAFE }

@Serializable
enum class BeatKind {
    START, DECISION, OUTCOME, SHOCK, LIFE_CHANGE, QUIET_PERIOD,
    CASH_NEGATIVE, CASH_RECOVERED, LOW_POINT, GOAL_REACHED, GOAL_MISSED, END
}

// How the luck of a decision is drawn, chosen before the game starts (docs/decision-tree.md):
// SAME_LEVEL = every decision at the same age gets the same luck (fair comparison between branches);
// FULL_RANDOM = each decision gets its own luck.
@Serializable
enum class LuckMode { SAME_LEVEL, FULL_RANDOM }

@Serializable
enum class Lang { ES, EN }

@Serializable
enum class ChatRole { USER, TWIN }

@Serializable
enum class Persona { FUTURE_SELF }

@Serializable
// EDUCATION takes a FactKey.EDUCATION code (e.g. "BACHELOR_IN_PROGRESS" = studying), independent from EMPLOYMENT.
// INVESTMENTS = invested money (CHF); DEBT = what is still owed on loans (CHF), a loan at the dataset median rate.
enum class ProfileField {
    AGE, CITY, EMPLOYMENT, EDUCATION, HOUSEHOLD, OPENING_CASH, INVESTMENTS, DEBT, MONTHLY_INCOME, MONTHLY_RENT, GOAL_TYPE,
    OBJECTIVE, MIN_CASH
}

// Content texts are written inline in both languages: {"ES": "...", "EN": "..."}.
typealias LocalizedText = Map<Lang, String>
