package com.lauzhack.twin.engine

import com.lauzhack.twin.model.Action
import com.lauzhack.twin.model.Attribution
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.InterviewQuestion
import com.lauzhack.twin.model.Preferences
import com.lauzhack.twin.model.Turn

/*
 * Contract between the UI and the engine (docs/decision-tree.md). The UI only talks to these two interfaces; the
 * real ones are RealGameEngine and ContentTwinFactory (wired in ui/App.kt).
 *
 * The game is a tree of decisions. A [GameSession] is the life from the start to one node of that tree (its turns);
 * the UI keeps the tree (ui/LifeTree.kt) and asks the engine what can be done at a node and what a decision gives.
 * Every function is pure: same inputs (including the seed and the luck mode) => same outputs, so any node can be
 * rebuilt and taking the same action at the same node always gives the same child.
 */

/** Everything needed to start a game (P2 output). */
data class NewGame(
    val opening: FinancialStatement,   // month 0 (person, money, goals)
    val preferences: Preferences,
)

/** P2: initial twin. */
interface TwinFactory {
    /** P1-P2 (`statement.new_person`): opening statement and preferences from the interview answers. */
    fun fromInterview(questions: List<InterviewQuestion>, answers: List<InterviewAnswer>): NewGame

    /** Alexia, the preloaded demo profile. */
    fun demo(): NewGame
}

interface GameEngine {
    /**
     * P3: the actions the player can take at the node [session] reaches (their requirements match the statement,
     * not taken yet on this path unless repeatable). "Carry on as you are" (NOTHING) is always first. Empty when the
     * game is over.
     */
    fun actionsAt(session: GameSession): List<Action>

    /**
     * P5-P7: takes [actionId] at the node [session] reaches. The luck of the new node (seed + luck mode) is the
     * percentile its uncertain numbers are drawn at; an involuntary shock may happen; `monthsPerTurn` months pass.
     * Nothing about the outcome is shown before this call (no preview of balances). Returns the new turn (with its story
     * moments, including the life changes due at the start of the turn).
     */
    fun decide(session: GameSession, actionId: String): Turn

    /** P8-P10 for the life [session]: stats, attribution and moments. */
    fun finish(session: GameSession): GameResult

    /**
     * Where the net worth of [session] comes from, from the opening statement to its last turn (start + sum of items
     * = end). Used to explain any number: the difference between two nodes is what happened in between.
     */
    fun attribution(session: GameSession): Attribution = finish(session).attribution

    /**
     * Optional (#12, option 2): cash percentiles per month (0..horizon) replaying the decisions with the simulator's
     * sampled monthly flows. Shown as "how much month-to-month noise moves your cash", NOT as the analysis (that is
     * the tree of decisions). null = the engine does not provide it.
     */
    fun noiseBand(session: GameSession): CashBand? = null
}

/** p10 / p50 / p90 of cash (CHF) per month, index = month (0..horizon). */
data class CashBand(
    val p10: List<Double>,
    val p50: List<Double>,
    val p90: List<Double>,
)

/** The game ends after `maxTurns` decisions. */
val GameSession.isOver: Boolean get() = turns.size >= config.maxTurns

/** The id of the node this life reaches in the tree: its action ids from the start ("" = start). */
val GameSession.nodeId: String get() = turns.joinToString("/") { it.actionId }

/** The actions taken on this life (an action that is not repeatable is taken once per life). */
val GameSession.usedActions: Set<String> get() = turns.mapTo(mutableSetOf()) { it.actionId }
