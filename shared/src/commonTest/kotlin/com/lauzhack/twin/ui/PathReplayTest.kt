package com.lauzhack.twin.ui

import com.lauzhack.twin.fake.FakeData
import com.lauzhack.twin.fake.FakeGameEngine
import com.lauzhack.twin.fake.FakeTwinFactory
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathReplayTest {
    private val engine = FakeGameEngine(FakeData.events, FakeData.assumptions)
    private val byId = { id: String -> FakeData.events.firstOrNull { it.id == id } }
    private val carTurn = 3 // E-car has earliestTurn = 3

    /** Plays always option A: studies, housing, invest, car with loan, then the car-loan question. */
    private fun played(): GameSession {
        val game = FakeTwinFactory().demo()
        var s = GameSession(game.profile, game.opening, 11L, game.preferences, emptyList(), GameConfig())
        while (!s.isOver) {
            val event = engine.nextEvent(s) ?: break
            val option = event.options.first()
            val (luck, case) = engine.resolveLuck(s, option)
            s = s.copy(turns = s.turns + engine.playTurn(s, event, option.id, case, luck))
        }
        return s
    }

    @Test
    fun playedPathAsksCarLoanQuestion() {
        val s = played()
        assertEquals(listOf("E-studies", "E-housing", "E-invest", "E-car", "E-car-loan"), s.turns.map { it.eventId })
    }

    @Test
    fun resolvingWithSamePathIsIdentical() {
        val s = played()
        val start = s.copy(turns = emptyList())
        val resolved = engine.resolvePath(start, s.turns, emptyMap(), byId)
        assertEquals(s, resolved.session)
        assertNull(resolved.pending)
    }

    @Test
    fun stillValidLaterQuestionsKeepTheirChoices() {
        val s = played()
        val edited = engine.editPath(s, 1, s.turns[1].choice.copy(optionId = "B"), emptyMap(), byId)
        assertNull(edited.pending)
        assertEquals(s.turns.map { it.eventId }, edited.session.turns.map { it.eventId })
        assertEquals(s.choices.drop(2), edited.session.choices.drop(2))
        assertEquals("B", edited.session.turns[1].chosenOptionId)
    }

    @Test
    fun invalidQuestionIsReplacedAndPending() {
        val s = played()
        // Travelcard instead of the car loan: no debt => the car-loan question is no longer valid.
        val edited = engine.editPath(s, carTurn, s.turns[carTurn].choice.copy(optionId = "B"), emptyMap(), byId)
        assertEquals(carTurn + 1, edited.session.turns.size)
        assertEquals("E-sabbatical", assertNotNull(edited.pending).id)
    }

    @Test
    fun branchMemoryRestoresDecisions() {
        val s = played()
        var memory = emptyMap<String, MemNode>().record(s)

        // Switch car to B, answer the new sabbatical question with B.
        val toB = engine.editPath(s, carTurn, s.turns[carTurn].choice.copy(optionId = "B"), memory, byId)
        val pending = assertNotNull(toB.pending)
        val sab = toB.session.copy(turns = toB.session.turns + engine.playTurn(toB.session, pending, "B", CaseType.OPTIMISTIC, 0.9))
        val branchB = engine.resolvePath(sab, emptyList(), memory, byId).session
        memory = memory.record(branchB)

        // Back to A: the car-loan question and its original answer come back.
        val backToA = engine.editPath(branchB, carTurn, s.turns[carTurn].choice, memory, byId)
        assertNull(backToA.pending)
        assertEquals(s, backToA.session)

        // And to B again: the sabbatical answer is remembered, nothing pending.
        val againB = engine.editPath(backToA.session, carTurn, branchB.turns[carTurn].choice, memory, byId)
        assertNull(againB.pending)
        assertEquals(branchB, againB.session)
    }

    @Test
    fun eligibilityFollowsRequirements() {
        val s = played()
        val carLoan = byId("E-car-loan")!!
        assertTrue(engine.isEligible(s.copy(turns = s.turns.take(4)), carLoan))
        val noLoan = engine.editPath(s, carTurn, s.turns[carTurn].choice.copy(optionId = "B"), emptyMap(), byId).session
        assertFalse(engine.isEligible(noLoan, carLoan))
    }
}
