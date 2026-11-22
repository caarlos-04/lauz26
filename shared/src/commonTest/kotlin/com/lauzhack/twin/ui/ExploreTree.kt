package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.GameEngine
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.NOTHING

/**
 * Tests only: explores the tree like a curious user. Every action available at the first [breadthDepth] decisions,
 * then "carry on as you are" until the end of the game.
 */
fun GameEngine.explore(tree: LifeTree, breadthDepth: Int = 2): LifeTree {
    var t = tree
    val stack = ArrayDeque(listOf(t.cursor))
    while (stack.isNotEmpty()) {
        val key = stack.removeLast()
        if (t.nodes.getValue(key).isOver) continue
        val ids = if (depthOf(key) < breadthDepth) actionsAt(t, key).map { it.id } else listOf(NOTHING)
        ids.forEach { id ->
            val (next, child) = decide(t, key, id)
            t = next
            stack.addLast(child)
        }
    }
    return t
}
