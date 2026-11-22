package com.lauzhack.twin.ui

import com.lauzhack.twin.content.AppContent
import com.lauzhack.twin.engine.RealGameEngine
import com.lauzhack.twin.engine.goalValue
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// F4: the goals the UI shows agree with the engine, in every life of Alexia and at every moment of it.
class GoalStatusTest {
    private val content = runBlocking { AppContent.load() }
    private val engine = RealGameEngine.from(content.flowModel, content.macro, content.events, content.assumptions)
    private val all = engine.explore(LifeTree.start(GameSession(content.alexia.statement, 2026L, content.alexia.preferences, emptyList(), GameConfig())))

    @Test
    fun reachedGoalsAreTheEngines() {
        all.lives.forEach { key ->
            val s = all.nodes.getValue(key)
            val ui = s.goalStatuses().filter { it.stage == GoalStage.REACHED }.map { it.goal.name }.toSet()
            assertEquals(engine.finish(s).stats.goalsReached.toSet(), ui, "life $key")
        }
    }

    @Test
    fun progressIsMeasuredLikeTheEngine() {
        all.nodes.values.forEach { s ->
            s.goalStatuses().forEach { g ->
                assertEquals(goalValue(g.goal, s.current), g.valueChf)
                assertTrue(g.progress in 0.0..1.0)
                if (g.stage == GoalStage.IN_PROGRESS) assertTrue(s.current.month < g.targetMonth + 1, "a goal past its deadline is reached or missed")
            }
        }
    }

    @Test
    fun emergencySavingsIsMeasuredOnCash() {
        val g = all.root.goalStatuses().first { it.goal.name == "Emergency savings" }
        assertTrue(g.onCash)
        assertEquals(all.root.initial.cashChf, g.valueChf)
    }
}
