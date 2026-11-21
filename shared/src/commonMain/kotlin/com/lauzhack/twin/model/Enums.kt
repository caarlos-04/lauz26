package com.lauzhack.twin.model

import kotlinx.serialization.Serializable

@Serializable
enum class Employment { STUDENT, EMPLOYED, PART_TIME, SELF_EMPLOYED, RETIRED, UNEMPLOYED }

@Serializable
enum class Household { SINGLE, COUPLE, FAMILY, SHARED_HOUSING }

// Order matters: the turn's luck is resolved in this order.
@Serializable
enum class CaseType { PESSIMISTIC, NEUTRAL, OPTIMISTIC }

@Serializable
enum class AssetType { CASH, INVESTMENT_PORTFOLIO }

@Serializable
enum class LiabilityType { PERSONAL_LOAN }

@Serializable
enum class ExpenseCategory { RENT, LIVING, TUITION, INSURANCE, TRAVEL, OTHER }

@Serializable
enum class FactKey { EDUCATION, OCCUPATION, CITY, HOUSING, FAMILY }

@Serializable
enum class Priority { HIGH, MEDIUM }

// Where an amount comes from.
@Serializable
enum class Origin { OBSERVED, DERIVED, ASSUMED, SIMULATED }

// Which index updates a stream when inflation is modelled.
@Serializable
enum class Indexation { WAGES, CPI, NONE }

@Serializable
enum class AccountType { CASH }

@Serializable
enum class AccountStatus { OK, NEGATIVE_REQUIRES_FINANCING }

@Serializable
enum class ProgressBasis { CASH, NET_WORTH }

@Serializable
enum class GoalState { IN_PROGRESS, REACHED, MISSED }

@Serializable
enum class LiquidityStatus { NEGATIVE, LOW, OK }

@Serializable
enum class Objective { GROWTH, BALANCED, SAFE }

@Serializable
enum class BeatKind {
    START, DECISION, CASE_RESOLVED, LIFE_CHANGE, QUIET_PERIOD,
    CASH_NEGATIVE, CASH_RECOVERED, LOW_POINT, GOAL_REACHED, GOAL_MISSED, END
}

@Serializable
enum class ParallelKind { OTHER_FIRST_CHOICE, OPTIMAL_STRATEGY }

@Serializable
enum class Lang { ES, EN }

@Serializable
enum class ChatRole { USER, TWIN }

@Serializable
enum class Persona { FUTURE_SELF, PARALLEL_SELF }

@Serializable
enum class ProfileField {
    AGE, CITY, EMPLOYMENT, HOUSEHOLD, OPENING_CASH, MONTHLY_INCOME, MONTHLY_RENT, GOAL_TYPE, OBJECTIVE, MIN_CASH
}

// Content texts are written inline in both languages: {"ES": "...", "EN": "..."}.
typealias LocalizedText = Map<Lang, String>
