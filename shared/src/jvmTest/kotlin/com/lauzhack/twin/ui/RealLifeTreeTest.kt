package com.lauzhack.twin.ui

import com.lauzhack.twin.content.AppContent
import com.lauzhack.twin.engine.RealGameEngine
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

// The life tree on the real engine and content, from Alexia: every possible life of the demo game.
class RealLifeTreeTest {
    private val content = runBlocking { AppContent.load() }
    private val engine = RealGameEngine.from(content.flowModel, content.macro, content.events, content.assumptions)
    private val start = LifeTree.start(GameSession(content.alexia.statement, 2026L, content.alexia.preferences, emptyList(), GameConfig()))

    @Test
    fun everyLifeOfAlexia() {
        val (all, took) = measureTimedValue { engine.expandAll(start) }
        val lives = all.lives
        println("Alexia: ${lives.size} lives, ${all.nodes.size} nodes, expanded in $took: $lives")
        assertTrue(lives.size in 2..32)
        lives.forEach { key ->
            val s = all.nodes.getValue(key)
            assertTrue(s.isOver)
            assertEquals(key, s.nodeKey)
        }
        // Same luck in every branch: turn 0 of every life has the same luck.
        assertEquals(1, lives.map { all.nodes.getValue(it).turns[0].luck }.toSet().size)
    }
}
