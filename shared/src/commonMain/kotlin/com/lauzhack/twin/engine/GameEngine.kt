package com.lauzhack.twin.engine

import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.EventOption
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.InterviewQuestion
import com.lauzhack.twin.model.OptionPreview
import com.lauzhack.twin.model.Preferences
import com.lauzhack.twin.model.Turn

/*
 * Contract between the UI (Dev B) and the engine / game controller (Dev A), docs section 6 (P2-P10).
 * PROPOSAL from the front branch: the UI only talks to these two interfaces, and runs on
 * FakeTwinFactory / FakeGameEngine until the real ones exist. Plugging the real engine in is a one-line
 * change in createDefaultAppState() (ui/App.kt).
 *
 * Every function is pure: same inputs (including the seed) => same outputs. The what-if decision tree
 * replays and re-plays paths, so this is required, not a nice-to-have.
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
     * P3: event for the next turn, or null for a quiet turn (no event fits).
     * MUST be deterministic: a pure function of the session (seed + turns so far); ties broken with the seed,
     * never with a global RNG.
     */
    fun nextEvent(session: GameSession): Event?

    /** True if [event] fits the current state of [session] (its `requires`, `earliestTurn`, `repeatable`). */
    fun isEligible(session: GameSession, event: Event): Boolean

    /** P4: deterministic preview of both options x 3 cases, advancing `monthsPerTurn` months. */
    fun preview(session: GameSession, event: Event): List<OptionPreview>

    /** P6: luck of the current turn (rng(seed, turnIndex)) and the case it selects for [option]. */
    fun resolveLuck(session: GameSession, option: EventOption): Pair<Double, CaseType>

    /**
     * P7: applies the case, advances the turn's months, applies life changes, detects moments and computes the
     * counterfactual of the other option. [event] == null plays a quiet turn (then optionId and case are null).
     * The UI always passes the case [luck] selects (the life tree never changes the luck of a turn).
     */
    fun playTurn(session: GameSession, event: Event?, optionId: String?, case: CaseType?, luck: Double): Turn

    /** P8-P10 at the end of the game: stats, attribution, moments, parallel lives and sensitivity. */
    fun finish(session: GameSession): GameResult

    /**
     * Optional (#12, option 2): cash percentiles per month (0..horizon) replaying the played choices with the
     * simulator's sampled monthly flows. Shown as "how much month-to-month noise moves your cash", NOT as the
     * analysis (that is the decision tree and the parallel lives). null = the engine does not provide it.
     */
    fun noiseBand(session: GameSession): CashBand? = null
}

/** p10 / p50 / p90 of cash (CHF) per month, index = month (0..horizon). */
data class CashBand(
    val p10: List<Double>,
    val p50: List<Double>,
    val p90: List<Double>,
)

/** The game ends after `maxTurns` turns (quiet turns count). */
val GameSession.isOver: Boolean get() = turns.size >= config.maxTurns
