package com.lauzhack.twin.engine

import com.lauzhack.twin.content.AppContent
import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.LuckMode
import com.lauzhack.twin.model.NOTHING
import com.lauzhack.twin.model.netWorthChf
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// #16: the UI's GameEngine on the real engine, with the packaged content, from Alexia's opening statement.
class RealGameEngineTest {

    private val content = runBlocking { AppContent.load() }
    private val engine = RealGameEngine.from(content.flowModel, content.macro, content.events, content.assumptions)
    private val twin = TwinEngine(Simulator(content.flowModel, content.macro,
        EngineRules(content.assumptions.financingRateAnnual, content.assumptions.investmentReturnAnnual)),
        content.events.actions, content.events.shocks)
    private val seed = 2026L

    private fun start(mode: LuckMode = LuckMode.SAME_LEVEL) =
        GameSession(content.alexia.statement, seed, content.alexia.preferences, emptyList(), GameConfig(), mode)

    /** A whole life through the GameEngine interface, as the UI plays it (one action per turn, NOTHING when missing). */
    private fun play(path: List<String>, mode: LuckMode = LuckMode.SAME_LEVEL): GameSession {
        var s = start(mode)
        while (!s.isOver) {
            val id = path.getOrElse(s.turns.size) { NOTHING }
            assertTrue(engine.actionsAt(s).any { it.id == id }, "$id available at turn ${s.turns.size}")
            s = s.copy(turns = s.turns + engine.decide(s, id))
        }
        return s
    }

    @Test
    fun sameLifeAsTheReferenceEngine() {
        for (mode in LuckMode.entries) for (path in listOf(listOf("A-master"), listOf("A-start-working", "A-shared-flat"), emptyList())) {
            val session = play(path, mode)
            val reference = twin.play(content.alexia.statement, path, seed, mode)
            assertEquals(reference.turns.map { it.actionId }, session.turns.map { it.actionId }, "$path")
            assertEquals(reference.turns.map { it.stateAfter }, session.turns.map { it.stateAfter }, "$path")
            val result = engine.finish(session)
            assertEquals(reference.attribution, result.attribution, "$path")
            val turnBeats = result.beats.filter { it.kind != BeatKind.START && it.kind != BeatKind.END && it.kind != BeatKind.LOW_POINT }
            assertEquals(reference.beats.filter { it.kind != BeatKind.START && it.kind != BeatKind.END }.toSet(), turnBeats.toSet(), "$path")
        }
    }

    @Test
    fun thePoolAlwaysOffersNothingAndOnlyWhatFits() {
        val ids = engine.actionsAt(start()).map { it.id }
        assertEquals(NOTHING, ids.first())
        assertTrue("A-master" in ids && "A-start-working" in ids)
        assertTrue("A-loan" !in ids, "cash is positive at the start")
        val afterMaster = play(listOf("A-master")).copy().let { it.copy(turns = it.turns.take(1)) }
        assertTrue("A-master" !in engine.actionsAt(afterMaster).map { it.id }, "not repeatable on the same life")
        assertFailsWith<IllegalArgumentException> { engine.decide(start(), "A-loan") }
    }

    @Test
    fun luckModes() {
        val root = start()
        val same = listOf("A-master", "A-start-working", NOTHING).map { engine.decide(root, it).luck }
        assertEquals(1, same.toSet().size, "SAME_LEVEL: siblings share the luck")
        val rootRandom = start(LuckMode.FULL_RANDOM)
        val own = listOf("A-master", "A-start-working", NOTHING).map { engine.decide(rootRandom, it).luck }
        assertEquals(3, own.toSet().size, "FULL_RANDOM: each decision has its own luck")
        assertEquals(engine.decide(rootRandom, "A-master"), engine.decide(rootRandom, "A-master"), "the same node gives the same child")
    }

    @Test
    fun nothingKeepsWhatIsUnderWay() {
        val s = play(listOf("A-master", NOTHING))
        assertEquals("MASTER", s.turns[1].stateAfter.person.facts[com.lauzhack.twin.model.FactKey.EDUCATION])
        assertNotEquals(s.turns[0].stateAfter, s.turns[1].stateAfter)
    }

    @Test
    fun resultsAddUpAndAreReproducible() {
        val session = play(listOf("A-master"))
        val r = engine.finish(session)
        val a = r.attribution
        assertTrue(abs(a.startNetWorth + a.items.sumOf { it.chf } - a.endNetWorth) < 0.05, "start + items = end")
        assertEquals(session.turns.last().stateAfter.netWorthChf(), r.stats.finalNetWorth)
        assertTrue(r.stats.minCash <= session.initial.cashChf)
        assertEquals(r.stats.goalsReached.size + r.stats.goalsMissed.size, session.initial.goals.size)
        assertEquals(BeatKind.START, r.beats.first().kind)
        assertEquals(BeatKind.END, r.beats.last().kind)
        assertEquals(r, engine.finish(session))                        // pure: the tree relies on it
    }

    @Test
    fun noiseBandCoversTheHorizonAndIsOrdered() {
        val session = play(listOf("A-start-working"))
        val band = engine.noiseBand(session, 60)
        val horizon = GameConfig().horizonMonths
        assertEquals(horizon + 1, band.p50.size)
        assertEquals(session.initial.cashChf, band.p50.first())
        for (m in 0..horizon) assertTrue(band.p10[m] <= band.p50[m] && band.p50[m] <= band.p90[m], "month $m")
        assertTrue(band.p90.last() > band.p10.last(), "sampled months spread the cash")
        assertEquals(band, engine.noiseBand(session, 60))               // seeded: reproducible
    }
}
