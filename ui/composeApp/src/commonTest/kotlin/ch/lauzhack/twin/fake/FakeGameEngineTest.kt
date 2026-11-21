package ch.lauzhack.twin.fake

import ch.lauzhack.twin.engine.PathKind
import ch.lauzhack.twin.model.CaseType
import ch.lauzhack.twin.model.GameConfig
import ch.lauzhack.twin.model.GameSession
import ch.lauzhack.twin.model.Lang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Sanity checks for the fake engine and demo data, so the UI never runs on inconsistent mocks.
class FakeGameEngineTest {
    private val engine = FakeGameEngine(FakeData.events, FakeData.assumptions)
    private fun newSession(seed: Long = 42L) =
        GameSession(FakeData.alexiaProfile, FakeData.alexiaState, seed, emptyList(), GameConfig())

    private fun playAll(seed: Long): GameSession {
        var s = newSession(seed)
        while (true) {
            val event = engine.nextEvent(s) ?: break
            val option = event.options.first()
            val case = engine.drawCase(option, s.seed, s.turns.size)
            s = s.copy(turns = s.turns + engine.playTurn(s, event, option.id, case))
        }
        return s
    }

    @Test
    fun catalogIsWellFormed() {
        FakeData.events.forEach { e ->
            assertEquals(2, e.options.size, e.id)
            e.options.forEach { o ->
                assertEquals(CaseType.entries.toSet(), o.cases.keys, "${e.id}/${o.id}")
                assertEquals(1.0, o.cases.values.sumOf { it.prob }, 1e-9, "${e.id}/${o.id}")
                o.cases.values.forEach { c -> assertTrue(c.narrative.keys.containsAll(Lang.entries)) }
            }
        }
    }

    @Test
    fun fullGameReachesHorizonAndIsReproducible() {
        val a = playAll(7L)
        val b = playAll(7L)
        assertEquals(GameConfig().maxTurns, a.turns.size)
        assertEquals(a, b)
        assertNull(engine.nextEvent(a))
        assertEquals(120, a.turns.last().stateAfter.month)
    }

    @Test
    fun previewHasTwoOptionsTimesThreeCases() {
        val s = newSession()
        val event = assertNotNull(engine.nextEvent(s))
        val previews = engine.preview(s, event, Lang.EN)
        assertEquals(2, previews.size)
        previews.forEach { assertEquals(3, it.perCase.size) }
    }

    @Test
    fun simulationBandsAreOrdered() {
        val sim = engine.simulate(playAll(3L), PathKind.CHOSEN, nPaths = 200)
        assertEquals(121, sim.netWorth.p50.size)
        for (m in 0..120) {
            assertTrue(sim.netWorth.p10[m] <= sim.netWorth.p50[m] && sim.netWorth.p50[m] <= sim.netWorth.p90[m])
        }
    }

    @Test
    fun monthWithoutEffectsMatchesCashFlow() {
        // Without growth/inflation drift in one step: cash changes by income - expenses.
        val s = FakeData.alexiaState
        val next = engine.advance(s, 1)[1]
        assertEquals(s.cash + s.monthlyNet, next.cash, 0.01)
    }
}
