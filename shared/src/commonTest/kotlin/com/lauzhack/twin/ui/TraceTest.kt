package com.lauzhack.twin.ui

import com.lauzhack.twin.fake.FakeData
import com.lauzhack.twin.fake.FakeTwinFactory
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceTest {
    private val engine = FakeData.engine()
    private val all = run {
        val game = FakeTwinFactory().demo()
        engine.explore(LifeTree.start(GameSession(game.opening, 11L, game.preferences, emptyList(), GameConfig())))
    }

    @Test
    fun everyTurnOfEveryLifeAddsUp() {
        all.nodes.keys.filter { it.isNotEmpty() }.forEach { key ->
            val t = engine.trace(all.nodes.getValue(parentOf(key)!!), all.nodes.getValue(key))
            assertTrue(abs(t.residual) < 0.01, "node $key: residual ${t.residual}")
            assertEquals(1, t.turns.size)
        }
    }

    @Test
    fun aWholeLifeIsTheSumOfItsTurns() {
        val life = all.lives.first()
        val whole = engine.trace(all.root, all.nodes.getValue(life))
        val byTurn = (0 until depthOf(life)).map { i -> engine.trace(all.nodes.getValue(ancestorAt(life, i)), all.nodes.getValue(ancestorAt(life, i + 1))) }
        whole.lines.forEach { line ->
            assertEquals(line.chf, byTurn.sumOf { t -> t.lines.firstOrNull { it.key == line.key }?.chf ?: 0.0 }, 0.01, line.key)
        }
        assertEquals(depthOf(life), whole.turns.size)
    }

    @Test
    fun originsOfTheMainCategories() {
        assertEquals(Origin.EVENT, originOf("income:salary"))
        assertEquals(Origin.EVENT, originOf("expense:TUITION"))
        assertEquals(Origin.DATASET, originOf("expense:groceries"))
        assertEquals(Origin.DATASET, originOf("income:bonus"))
        assertEquals(Origin.ASSUMPTION, originOf("financing_cost"))
        assertEquals(Origin.ASSUMPTION, originOf("investment_growth"))
    }
}
