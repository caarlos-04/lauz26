package com.lauzhack.twin.ui

import com.lauzhack.twin.content.AppContent
import com.lauzhack.twin.engine.RealGameEngine
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

// Traceability: on the real engine, every turn of every life of Alexia is explained to the franc.
class RealTraceTest {
    private val content = runBlocking { AppContent.load() }
    private val engine = RealGameEngine.from(content.flowModel, content.macro, content.events, content.assumptions)
    private val all = engine.explore(LifeTree.start(GameSession(content.alexia.statement, 2026L, content.alexia.preferences, emptyList(), GameConfig())))

    @Test
    fun everyTurnAddsUp() {
        all.nodes.keys.filter { it.isNotEmpty() }.forEach { key ->
            val t = engine.trace(all.nodes.getValue(parentOf(key)!!), all.nodes.getValue(key))
            assertTrue(abs(t.residual) < 0.01, "node $key: residual ${t.residual}")
            assertTrue(t.lines.isNotEmpty())
        }
    }

    @Test
    fun everyLifeAddsUp() {
        all.lives.forEach { key ->
            val t = engine.trace(all.root, all.nodes.getValue(key))
            assertTrue(abs(t.residual) < 0.01, "life $key: residual ${t.residual}")
        }
    }
}
