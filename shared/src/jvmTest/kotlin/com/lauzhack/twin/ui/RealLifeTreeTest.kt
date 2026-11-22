package com.lauzhack.twin.ui

import com.lauzhack.twin.content.AppContent
import com.lauzhack.twin.engine.RealGameEngine
import com.lauzhack.twin.engine.nodeId
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

// The tree of decisions on the real engine and content, from Alexia: every action of the first two decisions.
class RealLifeTreeTest {
    private val content = runBlocking { AppContent.load() }
    private val engine = RealGameEngine.from(content.flowModel, content.macro, content.events, content.assumptions)
    private val start = LifeTree.start(GameSession(content.alexia.statement, 2026L, content.alexia.preferences, emptyList(), GameConfig()))

    @Test
    fun manyLivesOfAlexia() {
        val (all, took) = measureTimedValue { engine.explore(start) }
        val lives = all.lives
        println("Alexia: ${lives.size} lives, ${all.nodes.size} nodes, explored in $took")
        assertTrue(lives.size >= 10, "several actions at each of the first two decisions")
        assertEquals(all.nodes.keys, all.decisions.nodes.keys, "one snapshot per decision taken")
        lives.forEach { key ->
            val s = all.nodes.getValue(key)
            assertTrue(s.isOver)
            assertEquals(key, s.nodeId)
            assertEquals(s.current, all.decisions.nodes.getValue(key).state)
        }
        // SAME_LEVEL: turn 0 of every life has the same luck.
        assertEquals(1, lives.map { all.nodes.getValue(it).turns[0].luck }.toSet().size)
    }
}
