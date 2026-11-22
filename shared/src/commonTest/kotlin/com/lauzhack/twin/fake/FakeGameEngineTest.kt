package com.lauzhack.twin.fake

import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.ParallelKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Sanity checks for the fake engine and demo data (main's model), so the UI never runs on inconsistent mocks.
class FakeGameEngineTest {
    private val engine = FakeGameEngine(FakeData.events, FakeData.assumptions)
    private val twins = FakeTwinFactory()

    private fun newSession(seed: Long = 42L): GameSession {
        val game = twins.demo()
        return GameSession(game.profile, game.opening, seed, game.preferences, emptyList(), GameConfig())
    }

    private fun playAll(start: GameSession): GameSession {
        var s = start
        while (!s.isOver) {
            val event = engine.nextEvent(s)
            s = if (event == null) {
                s.copy(turns = s.turns + engine.playTurn(s, null, null, null, 0.5))
            } else {
                val option = event.options.first()
                val (luck, case) = engine.resolveLuck(s, option)
                s.copy(turns = s.turns + engine.playTurn(s, event, option.id, case, luck))
            }
        }
        return s
    }

    @Test
    fun catalogIsWellFormed() {
        FakeData.events.forEach { e ->
            assertEquals(listOf("A", "B"), e.options.map { it.id }, e.id)
            assertTrue(e.title.keys.containsAll(Lang.entries), e.id)
            e.options.forEach { o ->
                assertEquals(CaseType.entries.toSet(), o.cases.keys, "${e.id}/${o.id}")
                assertEquals(1.0, o.cases.values.sumOf { it.prob }, 1e-9, "${e.id}/${o.id}")
                o.cases.values.forEach { c -> assertTrue(c.narrative.keys.containsAll(Lang.entries)) }
            }
        }
    }

    @Test
    fun fullGameReachesHorizonAndIsReproducible() {
        val a = playAll(newSession(7L))
        val b = playAll(newSession(7L))
        assertEquals(GameConfig().maxTurns, a.turns.size)
        assertEquals(a, b)
        assertNull(engine.nextEvent(a))
        assertEquals(120, a.turns.last().stateAfter.month)
    }

    @Test
    fun alexiaPlaysTheExpectedStory() {
        val s = playAll(newSession(11L))
        assertEquals(listOf("E-studies", "E-housing", "E-invest", "E-car", "E-car-loan"), s.turns.map { it.eventId })
        val end = s.turns.last().stateAfter.person
        assertEquals("MASTER", end.facts[com.lauzhack.twin.model.FactKey.EDUCATION])
        assertTrue("did_master" in end.flags && "took_loan" in end.flags)
    }

    @Test
    fun profileWithoutFittingEventsGetsAQuietTurn() {
        val employedSingle = listOf(InterviewAnswer("employment", "employed"), InterviewAnswer("household", "single"))
        val game = twins.fromInterview(FakeData.interview, employedSingle)
        val s = GameSession(game.profile, game.opening, 1L, game.preferences, emptyList(), GameConfig())
        assertNull(engine.nextEvent(s))
        val turn = engine.playTurn(s, null, null, null, 0.5)
        assertNull(turn.eventId)
        assertEquals(24, turn.stateAfter.month)
    }

    @Test
    fun previewHasTwoOptionsTimesThreeCases() {
        val s = newSession()
        val event = assertNotNull(engine.nextEvent(s))
        val previews = engine.preview(s, event)
        assertEquals(2, previews.size)
        previews.forEach { assertEquals(3, it.perCase.size) }
    }

    @Test
    fun finishBuildsConsistentResults() {
        val s = playAll(newSession(3L))
        val r = engine.finish(s)
        val band = r.cashBand
        assertEquals(121, band.p50.size)
        for (m in 0..120) assertTrue(band.p10[m] <= band.p50[m] && band.p50[m] <= band.p90[m])
        // Attribution closes: start + items = end.
        assertEquals(r.attribution.endNetWorth, r.attribution.startNetWorth + r.attribution.items.sumOf { it.chf }, 1.0)
        assertEquals(r.stats.finalNetWorth, s.turns.last().stateAfter.balanceSheet.netWorthChf, 1e-6)
        assertEquals(setOf(ParallelKind.OTHER_FIRST_CHOICE, ParallelKind.OPTIMAL_STRATEGY), r.parallelLives.map { it.kind }.toSet())
        val other = r.parallelLives.first { it.kind == ParallelKind.OTHER_FIRST_CHOICE }
        assertTrue(other.turns.first().chosenOptionId != s.turns.first().chosenOptionId)
    }

    @Test
    fun monthWithoutEffectsMatchesCashFlow() {
        val ledger = FakeLedger(FakeData.assumptions)
        val s = twins.demo().opening
        val next = ledger.step(s)
        assertTrue(abs(s.cash + s.metrics.monthlyNetCashFlowChf - next.cash) < 0.01)
    }
}
