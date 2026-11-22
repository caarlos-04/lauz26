package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.GameEngine
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.Action
import com.lauzhack.twin.model.DecisionNode
import com.lauzhack.twin.model.DecisionTree
import com.lauzhack.twin.model.GameSession

/**
 * Key of a node of the tree of decisions: the action ids from the start joined by "/" ("" = the start), e.g.
 * "A-master/NOTHING" = did a master's, then carried on for two years. Same as [DecisionNode.id].
 */
typealias NodeKey = String

/**
 * Every life the user has decided in one game, and where the user is now (docs/decision-tree.md).
 *
 * [decisions] is the tree itself: one snapshot per decision taken, nothing else. A node exists only because the user
 * took that action at that moment, so there are as many nodes as decisions (no lives computed on their own).
 * [nodes] is the life up to each node as the screens read it ([GameSession]), derived from the tree by the engine and
 * kept here so it is not rebuilt.
 *
 * Immutable: the operations ([decide], [choose], [goTo]) return a new tree. Nothing is ever forgotten.
 */
data class LifeTree(
    val decisions: DecisionTree,
    val nodes: Map<NodeKey, GameSession>,
    /** The node the user is at. */
    val cursor: NodeKey = DecisionTree.ROOT,
    /** For each node, the child visited last: coming back to a node goes down the same way again. */
    val lastChild: Map<NodeKey, NodeKey> = emptyMap(),
) {
    val root: GameSession get() = nodes.getValue(DecisionTree.ROOT)
    val current: GameSession get() = nodes.getValue(cursor)

    /** Complete lives (the game is over there), in key order. */
    val lives: List<NodeKey> get() = nodes.filterValues { it.isOver }.keys.sorted()

    /** Decisions taken in the whole tree (every node but the start). */
    val decisionCount: Int get() = nodes.size - 1

    fun children(key: NodeKey): List<NodeKey> = nodes.keys.filter { it.isNotEmpty() && parentOf(it) == key }.sorted()

    companion object {
        fun start(session: GameSession): LifeTree {
            require(session.turns.isEmpty()) { "A life tree starts at the opening statement" }
            val tree = DecisionTree.start(session.initial, session.seed, session.luckMode, session.preferences, session.config)
            return LifeTree(decisions = tree, nodes = mapOf(DecisionTree.ROOT to session))
        }
    }
}

/** The node before [key] (null for the start). */
fun parentOf(key: NodeKey): NodeKey? = DecisionTree.parentOf(key)

/** Number of decisions on the way to [key]. */
fun depthOf(key: NodeKey): Int = DecisionTree.depthOf(key)

/** The ancestor of [key] after [depth] decisions ([key] itself if it is not deeper). */
fun ancestorAt(key: NodeKey, depth: Int): NodeKey = if (depth <= 0) DecisionTree.ROOT else key.split('/').take(depth).joinToString("/")

/** The actions the user can take at node [key] (empty when the game is over there). */
fun GameEngine.actionsAt(tree: LifeTree, key: NodeKey = tree.cursor): List<Action> {
    val s = tree.nodes.getValue(key)
    return if (s.isOver) emptyList() else actionsAt(s)
}

/**
 * Takes [actionId] at node [key]: the child node, computed once (taking the same action at the same node again gives
 * the same child, so it is reused). The cursor does not move.
 */
fun GameEngine.decide(tree: LifeTree, key: NodeKey, actionId: String): Pair<LifeTree, NodeKey> {
    val childKey = DecisionTree.childId(key, actionId)
    if (childKey in tree.nodes) return tree to childKey
    val s = tree.nodes.getValue(key)
    check(!s.isOver) { "The game is over at '$key'" }
    val turn = decide(s, actionId)
    val child = s.copy(turns = s.turns + turn)
    val node = DecisionNode(id = childKey, parentId = key, depth = child.turns.size, actionId = actionId, luck = turn.luck,
                            shockId = turn.shockId, state = turn.stateAfter)
    return tree.copy(decisions = tree.decisions.copy(nodes = tree.decisions.nodes + (childKey to node)),
                     nodes = tree.nodes + (childKey to child)) to childKey
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
    return copy(cursor = key, lastChild = down)
}

/** Live play: takes [actionId] at the cursor and moves there. */
fun GameEngine.choose(tree: LifeTree, actionId: String): LifeTree {
    val (t, child) = decide(tree, tree.cursor, actionId)
    return t.goTo(child)
}
