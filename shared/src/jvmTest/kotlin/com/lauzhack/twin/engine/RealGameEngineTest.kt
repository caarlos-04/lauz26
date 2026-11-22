package com.lauzhack.twin.engine

import com.lauzhack.twin.content.AppContent
import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.ParallelKind
import com.lauzhack.twin.model.netWorthChf
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// #16: the UI's GameEngine on the real engine, with the packaged content, from Alexia's opening statement.
class RealGameEngineTest {

    private val content = runBlocking { AppContent.load() }
    private val engine = RealGameEngine.from(content.flowModel, content.macro, content.events, content.assumptions)
    private val twin = TwinEngine(Simulator(content.flowModel, content.macro,
        EngineRules(content.assumptions.financingRateAnnual, content.assumptions.investmentReturnAnnual)), content.events.events)
    private val seed = 2026L

    /** A whole game through the GameEngine interface, as the UI plays it (option per turn, case from the luck). */
    private fun play(choices: List<String>): GameSession {
        var s = GameSession(content.alexia.statement, seed, content.alexia.preferences, emptyList(), GameConfig())
        while (!s.isOver) {
            val i = s.turns.size
            val event = engine.nextEvent(s)
            val turn = if (event == null) engine.playTurn(s, null, null, null, PortableRandom.turnLuck(seed, i)) else {
                val option = event.options.first { it.id == choices.getOrElse(i) { "A" } }
                val (luck, case) = engine.resolveLuck(s, option)
                engine.playTurn(s, event, option.id, case, luck)
            }
            s = s.copy(turns = s.turns + turn)
        }
        return s
    }

    @Test
    fun sameGameAsTheReferenceEngine() {
        for (choices in listOf(listOf("A", "B"), listOf("B", "A", "B"), emptyList())) {
            val session = play(choices)
            val reference = twin.play(content.alexia.statement, choices, seed)
            assertEquals(reference.turns.map { it.eventId }, session.turns.map { it.eventId }, "$choices")
            assertEquals(reference.turns.map { it.stateAfter }, session.turns.map { it.stateAfter }, "$choices")
            val result = engine.finish(session)
            assertEquals(reference.attribution, result.attribution, "$choices")
            val turnBeats = result.beats.filter { it.kind != BeatKind.START && it.kind != BeatKind.END && it.kind != BeatKind.LOW_POINT }
            assertEquals(reference.beats.filter { it.kind != BeatKind.START && it.kind != BeatKind.END }.toSet(), turnBeats.toSet(), "$choices")
        }
    }

    @Test
    fun resultsAddUpAndAreReproducible() {
        val session = play(listOf("A", "B"))
        val r = engine.finish(session)
        val a = r.attribution
        assertTrue(abs(a.startNetWorth + a.items.sumOf { it.chf } - a.endNetWorth) < 0.05, "start + items = end")
        assertEquals(session.turns.last().stateAfter.netWorthChf(), r.stats.finalNetWorth)
        assertTrue(r.stats.minCash <= session.initial.cashChf)
        assertEquals(r.stats.goalsReached.size + r.stats.goalsMissed.size, session.initial.goals.size)
        assertEquals(BeatKind.START, r.beats.first().kind)
        assertEquals(BeatKind.END, r.beats.last().kind)
        assertEquals(r, engine.finish(session))                        // pure: the what-if tree relies on it
    }

    @Test
    fun parallelLivesFlipTheFirstDecisionWithTheSameLuck() {
        val session = play(listOf("A", "A"))
        val lives = engine.finish(session).parallelLives
        assertEquals(listOf(ParallelKind.OTHER_FIRST_CHOICE, ParallelKind.OPTIMAL_STRATEGY), lives.map { it.kind })
        val first = session.turns.indexOfFirst { it.eventId != null }
        val other = lives.first().turns[first]
        assertEquals(session.turns[first].eventId, other.eventId)
        assertNotEquals(session.turns[first].chosenOptionId, other.chosenOptionId)
        assertEquals(session.turns[first].luck, other.luck)
        for (life in lives) {
            assertEquals(GameConfig().maxTurns, life.turns.size)
            val at = life.attribution
            assertTrue(abs(at.startNetWorth + at.items.sumOf { it.chf } - at.endNetWorth) < 0.05)
        }
    }

    @Test
    fun noiseBandCoversTheHorizonAndIsOrdered() {
        val session = play(listOf("A", "B"))
        val band = engine.noiseBand(session, 60)
        val horizon = GameConfig().horizonMonths
        assertEquals(horizon + 1, band.p50.size)
        assertEquals(session.initial.cashChf, band.p50.first())
        for (m in 0..horizon) assertTrue(band.p10[m] <= band.p50[m] && band.p50[m] <= band.p90[m], "month $m")
        assertTrue(band.p90.last() > band.p10.last(), "sampled months spread the cash")
        assertEquals(band, engine.noiseBand(session, 60))               // seeded: reproducible
    }
}
