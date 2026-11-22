package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.GameEngine
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.Turn

/** The user's choice at one turn: which option and which case resolved (null for a quiet turn), with its luck. */
data class TurnChoice(val optionId: String?, val case: CaseType?, val luck: Double)

/** Choices of the turns currently in the session, in order. */
val GameSession.choices: List<TurnChoice>
    get() = turns.map { it.choice }

val Turn.choice: TurnChoice get() = TurnChoice(chosenOptionId, resolvedCase, luck)

/** One remembered node of the tree: the question asked after a given prefix (null = quiet) and what was answered. */
data class MemNode(val eventId: String?, val choice: TurnChoice)

/**
 * Explored branches of the tree. Key = signature of the turns before the node, so the same prefix
 * (same events, options and cases => same state) always maps to the same question and answer.
 */
typealias PathMemory = Map<String, MemNode>

/** Result of resolving a path: the turns that could be played, and the first unanswered question if any. */
data class ResolvedPath(val session: GameSession, val pending: Event?)

fun signature(turns: List<Turn>): String =
    turns.joinToString("|") { "${it.eventId ?: "-"}:${it.chosenOptionId ?: "-"}:${it.resolvedCase ?: "-"}" }

/** Stores every node of the session's path in the memory. */
fun PathMemory.record(session: GameSession): PathMemory {
    val out = toMutableMap()
    session.turns.forEachIndexed { i, turn ->
        out[signature(session.turns.take(i))] = MemNode(turn.eventId, turn.choice)
    }
    return out
}

/**
 * Continues [start] until the end of the game, keeping questions consistent with the state:
 * - Event at turn i: the remembered one for this exact prefix; else the [previous] path's event at i
 *   if it is still eligible ("keep if still valid"); else a new pick via [GameEngine.nextEvent];
 *   if nothing fits, a quiet turn is played.
 * - Choice: the remembered one; else the previous path's choice if the event is the same;
 *   else the path stops there and the event is returned as `pending` for the user to decide.
 * Pure: only uses the engine's deterministic functions.
 */
fun GameEngine.resolvePath(
    start: GameSession,
    previous: List<Turn>,
    memory: PathMemory,
    eventById: (String) -> Event?,
): ResolvedPath {
    var s = start
    while (!s.isOver) {
        val i = s.turns.size
        val remembered = memory[signature(s.turns)]
        val prev = previous.getOrNull(i)

        val event = remembered?.eventId?.let(eventById)?.takeIf { isEligible(s, it) }
            ?: prev?.eventId?.let(eventById)?.takeIf { isEligible(s, it) }
            ?: nextEvent(s)

        if (event == null) {
            val luck = prev?.luck ?: remembered?.choice?.luck ?: 0.5
            s = s.copy(turns = s.turns + playTurn(s, null, null, null, luck))
            continue
        }

        val choice = remembered?.takeIf { it.eventId == event.id }?.choice
            ?: prev?.takeIf { it.eventId == event.id }?.choice
            ?: return ResolvedPath(s, pending = event)

        s = s.copy(turns = s.turns + playTurn(s, event, choice.optionId, choice.case, choice.luck))
    }
    return ResolvedPath(s, pending = null)
}

/**
 * Changes the choice of turn [index] and re-resolves everything after it.
 * Turns before [index] are untouched; the event at [index] is kept (it was valid for the same prefix).
 * Quiet turns cannot be edited.
 */
fun GameEngine.editPath(
    session: GameSession,
    index: Int,
    newChoice: TurnChoice,
    memory: PathMemory,
    eventById: (String) -> Event?,
): ResolvedPath {
    val old = session.turns[index]
    val event = old.eventId?.let(eventById) ?: error("Turn $index is quiet or its event is unknown")
    val prefix = session.copy(turns = session.turns.take(index))
    val edited = prefix.copy(turns = prefix.turns + playTurn(prefix, event, newChoice.optionId, newChoice.case, newChoice.luck))
    return resolvePath(edited, session.turns, memory, eventById)
}
