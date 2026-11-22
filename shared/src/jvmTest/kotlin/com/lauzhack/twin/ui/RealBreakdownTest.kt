package com.lauzhack.twin.ui

import com.lauzhack.twin.content.AppContent
import com.lauzhack.twin.engine.RealGameEngine
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.LuckMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

// Traceability on the real engine and content: every figure of every month of every life of Alexia is explained to
// the franc (the Explore panel), in both luck modes, and agrees with the engine's attribution.
class RealBreakdownTest {
    private val content = runBlocking { AppContent.load() }
    private val engine = RealGameEngine.from(content.flowModel, content.macro, content.events, content.assumptions)

    private fun explored(mode: LuckMode) =
        engine.explore(LifeTree.start(GameSession(content.alexia.statement, 2026L, content.alexia.preferences, emptyList(), GameConfig(), mode)))

    @Test
    fun everyFigureOfEveryMomentAddsUp() {
        val all = explored(LuckMode.SAME_LEVEL)
        val checked = assertEveryBreakdownAddsUp(engine, all)
        println("Alexia: ${all.nodes.size} nodes, $checked breakdowns add up")
        assertTrue(checked > 10_000)
    }

    @Test
    fun alsoWithItsOwnLuckPerDecision() {
        assertTrue(assertEveryBreakdownAddsUp(engine, explored(LuckMode.FULL_RANDOM)) > 10_000)
    }

    @Test
    fun theLivesOfAlexiaShowLoansAndNegativeCash() {
        // The checks above mean little if no life ever has a loan or negative cash: make sure some do.
        val all = explored(LuckMode.SAME_LEVEL)
        val lives = all.lives.map { engine.months(all.nodes.getValue(it)) }
        assertTrue(lives.any { months -> months.any { (it.loan?.principalChf ?: 0.0) > 0 } }, "no life with a loan")
        assertTrue(lives.any { months -> months.any { (it.cashFlow?.financingCostChf ?: 0.0) > 0 } }, "no life with negative cash")
    }
}
