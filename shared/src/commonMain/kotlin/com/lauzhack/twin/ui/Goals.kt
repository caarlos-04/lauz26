package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.GoalState
import com.lauzhack.twin.engine.goalOnCash
import com.lauzhack.twin.engine.goalState
import com.lauzhack.twin.engine.goalValue
import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.Goal

enum class GoalStage { IN_PROGRESS, REACHED, MISSED }

/**
 * Where one goal stands at a node of the life, with the engine's rules (engine/Basics.kt): savings-type goals are
 * measured on cash, the rest on net worth; a goal is reached the first month its value hits the target before the
 * deadline, and missed if the deadline passes first. [whenMonth] / [whenAge] = when it was reached or missed.
 */
data class GoalStatus(
    val goal: Goal,
    val valueChf: Double,        // what the goal is measured on, now
    val progress: Double,        // valueChf / target, 0..1, now
    val onCash: Boolean,         // measured on cash (else on net worth)
    val targetMonth: Int,        // deadline, months since the start
    val stage: GoalStage,
    val whenMonth: Int? = null,
    val whenAge: Int? = null,
)

/** The goals at the current node of the session, from the moments (beats) the engine recorded. */
fun GameSession.goalStatuses(): List<GoalStatus> {
    val now = current
    val beats = turns.flatMap { it.beats }
    return now.goals.map { g ->
        val value = goalValue(g, now)
        val deadline = g.targetMonth(initial)
        val reached = beats.firstOrNull { it.kind == BeatKind.GOAL_REACHED && it.data["goal"] == g.name }
        val missed = beats.firstOrNull { it.kind == BeatKind.GOAL_MISSED && it.data["goal"] == g.name }
        val (stage, month, age) = when {
            goalState(g, initial) == GoalState.REACHED -> Triple(GoalStage.REACHED, 0, initial.person.age)
            reached != null -> Triple(GoalStage.REACHED, reached.month, reached.age)
            missed != null -> Triple(GoalStage.MISSED, missed.month, missed.age)
            else -> Triple(GoalStage.IN_PROGRESS, null, null)
        }
        GoalStatus(
            goal = g,
            valueChf = value,
            progress = if (g.targetChf <= 0) 1.0 else (value / g.targetChf).coerceIn(0.0, 1.0),
            onCash = goalOnCash(g),
            targetMonth = deadline,
            stage = stage,
            whenMonth = month,
            whenAge = age,
        )
    }
}
