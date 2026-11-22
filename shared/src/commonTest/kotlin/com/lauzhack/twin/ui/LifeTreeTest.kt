package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.fake.FakeData
import com.lauzhack.twin.fake.FakeGameEngine
import com.lauzhack.twin.fake.FakeTwinFactory
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifeTreeTest {
    private val engine = FakeGameEngine(FakeData.events, FakeData.assumptions)
    private val carTurn = 3 // E-car has earliestTurn = 3

    private fun start(): LifeTree {
        val game = FakeTwinFactory().demo()
        return LifeTree.start(GameSession(game.opening, 11L, game.preferences, emptyList(), GameConfig()))
    }

    /** Live play: always option A (quiet turns are played as they come). */
    private fun playAllA(tree: LifeTree = start()): LifeTree {
        var t = tree
        while (!t.current.isOver) t = if (engine.questionAt(t) == null) engine.playQuiet(t) else engine.choose(t, "A")
        return t
    }

    @Test
    fun keyIsTheChoices() {
        val t = playAllA()
        assertEquals(listOf("E-studies", "E-housing", "E-hours", "E-car", "E-car-loan"), t.current.turns.map { it.eventId })
        assertEquals("AAAAA", t.cursor)
        assertEquals(t.cursor, t.current.nodeKey)
        assertEquals(setOf("", "A", "AA", "AAA", "AAAA", "AAAAA"), t.visited)
    }

    @Test
    fun sameChoicesGiveTheSameLife() {
        assertEquals(playAllA().current, playAllA().current)
    }

    @Test
    fun luckIsFixedPerTurnInEveryBranch() {
        val all = engine.expandAll(start())
        all.nodes.values.forEach { s ->
            s.turns.forEachIndexed { i, turn ->
                if (turn.eventId != null) assertEquals(engine.resolveLuck(s.copy(turns = s.turns.take(i)), FakeData.events.first { it.id == turn.eventId }.options.first()).first, turn.luck)
            }
        }
        // Turn 0 has the same luck in both branches.
        assertEquals(all.nodes.getValue("A").turns[0].luck, all.nodes.getValue("B").turns[0].luck)
    }

    @Test
    fun switchingKeepsEarlierTurnsAndRemembersTheOtherBranch() {
        val played = playAllA()
        val b = engine.switchAt(played, carTurn)
        assertEquals("AAAB", b.cursor.take(4))
        assertEquals(played.current.turns.take(carTurn), b.current.turns.take(carTurn))
        assertTrue("AAAAA" in b.nodes, "the played life is never forgotten")

        // Going back to A at the same turn goes down the life already played, nothing pending.
        val backToA = engine.switchAt(b, carTurn)
        assertEquals("AAAAA", backToA.cursor)
        assertEquals(played.current, backToA.current)
    }

    @Test
    fun aDifferentBranchCanGetADifferentQuestion() {
        val b = engine.switchAt(playAllA(), carTurn)
        // Travelcard instead of the car loan: no debt => the car-loan question does not come; another one waits.
        assertEquals("AAAB", b.cursor)
        assertEquals("E-sabbatical", assertNotNull(engine.questionAt(b)).id)
        val answered = engine.descend(engine.choose(b, "B"))
        assertEquals("AAABB", answered.cursor)
        assertTrue(answered.current.isOver)

        // Back to A, then to B again: the sabbatical answer is remembered.
        val again = engine.switchAt(engine.switchAt(answered, carTurn), carTurn)
        assertEquals("AAABB", again.cursor)
    }

    @Test
    fun goToRemembersTheWayDown() {
        val b = engine.descend(engine.choose(engine.switchAt(playAllA(), carTurn), "B"))
        val atStart = b.goTo("")
        assertEquals("", atStart.cursor)
        assertEquals("AAABB", engine.descend(atStart).cursor)
    }

    @Test
    fun everyLifeIsAtMost2PowTurnsAndComplete() {
        val all = engine.expandAll(start())
        val lives = all.lives
        assertTrue(lives.size in 2..32, "lives: ${lives.size}")
        assertEquals(lives.size, lives.toSet().size)
        lives.forEach { key ->
            val s = all.nodes.getValue(key)
            assertTrue(s.isOver)
            assertEquals(key, s.nodeKey)
        }
        // Expanding does not move the user nor mark anything as visited.
        assertEquals("", all.cursor)
        assertEquals(setOf(""), all.visited)
    }

    @Test
    fun differentLivesEndDifferently() {
        val all = engine.expandAll(start())
        val ends = all.lives.map { all.nodes.getValue(it).turns.last().stateAfter }
        assertNotEquals(1, ends.toSet().size)
    }

    @Test
    fun quietTurnsAreNotChoices() {
        val t = start()
        assertFailsWith<IllegalStateException> { engine.withChild(t, "", null) }   // turn 0 has a question
        assertNull(siblingOf("A").takeIf { it == "A" })
        assertEquals("B", siblingOf("A"))
        assertFailsWith<IllegalArgumentException> { siblingOf("A-") }
    }
}
