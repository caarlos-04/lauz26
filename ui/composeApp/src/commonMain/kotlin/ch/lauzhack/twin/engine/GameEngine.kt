package ch.lauzhack.twin.engine

import ch.lauzhack.twin.model.CaseType
import ch.lauzhack.twin.model.Event
import ch.lauzhack.twin.model.EventOption
import ch.lauzhack.twin.model.GameSession
import ch.lauzhack.twin.model.Lang
import ch.lauzhack.twin.model.OptionPreview
import ch.lauzhack.twin.model.PathComparison
import ch.lauzhack.twin.model.SimulationResult
import ch.lauzhack.twin.model.Stats
import ch.lauzhack.twin.model.Turn

/** Which path of the game to evaluate at the end. */
enum class PathKind { CHOSEN, ALTERNATIVE }

/**
 * Contract between the UI and the financial engine (Dev A).
 * The UI only talks to this interface; the UI ships a FakeGameEngine until the real one is plugged in.
 * Every function must be pure: same inputs (including seed) => same outputs.
 */
interface GameEngine {
    /**
     * P3: next event for this session, or null when the game is over.
     * MUST be deterministic: a pure function of the session (seed + turns so far). If the pool is
     * drawn randomly, seed the RNG with `seed + turnIndex`, never a global RNG — the what-if tree
     * replays paths and relies on getting the same question for the same path.
     */
    fun nextEvent(session: GameSession): Event?

    /** True if [event] fits the profile and the current state of [session] (its `requires`). */
    fun isEligible(session: GameSession, event: Event): Boolean

    /** P4: deterministic preview of both options x 3 cases, advancing `monthsPerTurn` months. */
    fun preview(session: GameSession, event: Event, lang: Lang): List<OptionPreview>

    /** P6: draw a case using an RNG derived from seed + turnIndex. */
    fun drawCase(option: EventOption, seed: Long, turnIndex: Int): CaseType

    /** P7: apply the case, advance time and compute the counterfactual of the other option. */
    fun playTurn(session: GameSession, event: Event, optionId: String, case: CaseType): Turn

    /** P8: Monte Carlo fan for the chosen path or the alternative one, over the full horizon. */
    fun simulate(session: GameSession, path: PathKind, nPaths: Int = 500): SimulationResult

    /** P9: deterministic stats for one path. */
    fun stats(session: GameSession, path: PathKind): Stats

    /**
     * P9: chosen vs alternative. `explanation` holds event ids ordered by absolute impact
     * on net worth (largest first); the UI turns them into localized text.
     */
    fun compare(session: GameSession): PathComparison
}
