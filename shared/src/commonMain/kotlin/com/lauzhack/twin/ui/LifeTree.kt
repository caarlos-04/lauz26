package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.GameEngine
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.GameSession

/**
 * Key of a node of the life tree: one character per played turn, the chosen option id ("A" / "B") or [QUIET] for a
 * quiet turn. "" is the start of the game. Example: "AB-" = option A, then B, then a quiet turn.
 */
typealias NodeKey = String

const val QUIET = '-'

/** The key of the node a session is at. */
val GameSession.nodeKey: NodeKey get() = turns.joinToString("") { it.chosenOptionId ?: QUIET.toString() }

/**
 * Every life explored in one game, and where the user is now.
 *
 * A life is fully determined by its choices: the luck of turn i is the same in every branch (rng(seed, i)), so the
 * case drawn can never be changed, only the option. Events are picked from the state of each branch, so different
 * branches can get different questions or quiet turns: with 5 turns there are **up to** 2^5 = 32 lives.
 *
 * Immutable: the operations ([choose], [playQuiet], [goTo], [switchAt], [descend]) return a new tree. Nothing is
 * ever forgotten: every node computed stays in [nodes].
 */
data class LifeTree(
    /** Every computed node: the session with the turns up to that node. Always contains the start (""). */
    val nodes: Map<NodeKey, GameSession>,
    /** The node the user is at. */
    val cursor: NodeKey = "",
    /** Nodes the user has been at (a subset of [nodes]). */
    val visited: Set<NodeKey> = setOf(""),
    /** For each node, the child visited last: coming back to a node goes down the same way again. */
    val lastChild: Map<NodeKey, NodeKey> = emptyMap(),
) {
    val root: GameSession get() = nodes.getValue("")
    val current: GameSession get() = nodes.getValue(cursor)

    /** Visited nodes where the game is over (complete lives). */
    val visitedLives: List<NodeKey> get() = visited.filter { nodes.getValue(it).isOver }.sorted()

    companion object {
        fun start(session: GameSession): LifeTree {
            require(session.turns.isEmpty()) { "A life tree starts at the opening statement" }
            return LifeTree(nodes = mapOf("" to session))
        }
    }
}

/** The node before [key] (null for the start). */
fun parentOf(key: NodeKey): NodeKey? = if (key.isEmpty()) null else key.dropLast(1)

/** The key of the same turn with the other option ("A" <-> "B"). Events always have exactly options A and B. */
fun siblingOf(key: NodeKey): NodeKey {
    val last = key.lastOrNull() ?: error("The start has no sibling")
    require(last != QUIET) { "A quiet turn has no other option" }
    return key.dropLast(1) + if (last == 'A') 'B' else 'A'
}

/** The question asked at node [key], or null if the game is over there or the next turn is quiet. */
fun GameEngine.questionAt(tree: LifeTree, key: NodeKey = tree.cursor): Event? {
    val s = tree.nodes.getValue(key)
    return if (s.isOver) null else nextEvent(s)
}

/** Computes the child of [key] for [optionId] (null = quiet turn) if it is not in the tree yet. Cursor unchanged. */
fun GameEngine.withChild(tree: LifeTree, key: NodeKey, optionId: String?): Pair<LifeTree, NodeKey> {
    val childKey = key + (optionId ?: QUIET.toString())
    if (childKey in tree.nodes) return tree to childKey
    val s = tree.nodes.getValue(key)
    check(!s.isOver) { "The game is over at '$key'" }
    val event = nextEvent(s)
    val turn = if (optionId == null) {
        check(event == null) { "Turn ${s.turns.size} at '$key' has a question (${event?.id}): choose an option" }
        playTurn(s, null, null, null, QUIET_LUCK)
    } else {
        checkNotNull(event) { "Turn ${s.turns.size} at '$key' is quiet: there is no option $optionId" }
        val option = event.options.first { it.id == optionId }
        val (luck, case) = resolveLuck(s, option)       // fixed per turn: the same luck in every branch
        playTurn(s, event, optionId, case, luck)
    }
    return tree.copy(nodes = tree.nodes + (childKey to s.copy(turns = s.turns + turn))) to childKey
}

/** Moves the cursor to an existing node; every ancestor remembers the way down to it. */
fun LifeTree.goTo(key: NodeKey): LifeTree {
    require(key in nodes) { "Unknown node '$key'" }
    val down = lastChild.toMutableMap()
    var child = key
    var parent = parentOf(child)
    while (parent != null) {
        down[parent] = child
        child = parent
        parent = parentOf(child)
    }
    return copy(cursor = key, visited = visited + key, lastChild = down)
}

/** Live play: takes [optionId] at the cursor's question and moves there. */
fun GameEngine.choose(tree: LifeTree, optionId: String): LifeTree {
    val (t, child) = withChild(tree, tree.cursor, optionId)
    return t.goTo(child)
}

/** Live play: the next turn has no question; 24 months pass. */
fun GameEngine.playQuiet(tree: LifeTree): LifeTree {
    val (t, child) = withChild(tree, tree.cursor, null)
    return t.goTo(child)
}

/**
 * From the cursor, goes down as far as possible without asking anything new: follows the child visited last from
 * each node and plays quiet turns. Stops at the end of the game or at a question never answered on this branch.
 */
fun GameEngine.descend(tree: LifeTree): LifeTree {
    var t = tree
    while (!t.current.isOver) {
        val next = t.lastChild[t.cursor]
        t = when {
            next != null && next in t.nodes -> t.goTo(next)
            questionAt(t) == null -> playQuiet(t)
            else -> return t
        }
    }
    return t
}

/**
 * "What if I had chosen the other option at turn [turnIndex]?": moves to that branch (computing it if needed) and
 * goes down it as far as it was already explored (see [descend]). Turns before [turnIndex] do not change.
 */
fun GameEngine.switchAt(tree: LifeTree, turnIndex: Int): LifeTree {
    require(turnIndex < tree.cursor.length) { "Turn $turnIndex is not played on this branch" }
    val here = tree.cursor.take(turnIndex + 1)
    val other = siblingOf(here)
    val (t, key) = withChild(tree, here.dropLast(1), other.last().toString())
    return descend(t.goTo(key))
}

/**
 * Every possible life of the game from the start (up to 2^turns leaves), computed but not visited. For the map of
 * the tree and to compare lives; cheap: at most 63 turns of 24 months.
 */
fun GameEngine.expandAll(tree: LifeTree): LifeTree {
    var t = tree
    val stack = ArrayDeque(listOf(""))
    while (stack.isNotEmpty()) {
        val key = stack.removeLast()
        if (t.nodes.getValue(key).isOver) continue
        val event = questionAt(t, key)
        val options = event?.options?.map { it.id } ?: listOf(null)
        options.forEach { id ->
            val (next, child) = withChild(t, key, id)
            t = next
            stack.addLast(child)
        }
    }
    return t
}

/** The complete lives (leaves) in [LifeTree.nodes], in key order. */
val LifeTree.lives: List<NodeKey> get() = nodes.filterValues { it.isOver }.keys.sorted()

/** Luck stored for quiet turns (there is no case to draw). */
private const val QUIET_LUCK = 0.5
