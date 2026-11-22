package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.engine.nodeId
import com.lauzhack.twin.fake.FakeData
import com.lauzhack.twin.fake.FakeTwinFactory
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.LuckMode
import com.lauzhack.twin.model.NOTHING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// The tree of decisions (docs/decision-tree.md) on the real engine with the small model of FakeData.
class LifeTreeTest {
    private val engine = FakeData.engine()

    private fun start(mode: LuckMode = LuckMode.SAME_LEVEL): LifeTree {
        val game = FakeTwinFactory().demo()
        return LifeTree.start(GameSession(game.opening, 11L, game.preferences, emptyList(), GameConfig(), mode))
    }

    /** Live play: the given actions, then "carry on as you are" to the end. */
    private fun play(vararg path: String, tree: LifeTree = start()): LifeTree {
        var t = tree
        var i = 0
        while (!t.current.isOver) t = engine.choose(t, path.getOrElse(i++) { NOTHING })
        return t
    }

    @Test
    fun theKeyIsThePathOfActions() {
        val t = play("A-master")
        assertEquals("A-master/NOTHING/NOTHING/NOTHING/NOTHING", t.cursor)
        assertEquals(t.cursor, t.current.nodeId)
        assertEquals(6, t.nodes.size, "one node per decision taken, nothing else")
        assertEquals(t.nodes.keys, t.decisions.nodes.keys)
        assertEquals(t.current.turns.last().stateAfter, t.decisions.nodes.getValue(t.cursor).state)
        assertEquals("A-master/NOTHING", ancestorAt(t.cursor, 2))
        assertEquals("A-master", parentOf("A-master/NOTHING"))
        assertEquals(3, depthOf("A-master/NOTHING/NOTHING"))
    }

    @Test
    fun thePoolOffersEveryActionThatFitsAndNothingFirst() {
        val ids = engine.actionsAt(start()).map { it.id }
        assertEquals(NOTHING, ids.first())
        assertTrue("A-master" in ids && "A-start-working" in ids)
        assertTrue("A-loan" !in ids, "cash is positive")
        val afterMaster = engine.choose(start(), "A-master")
        assertTrue("A-master" !in engine.actionsAt(afterMaster).map { it.id }, "taken once per life")
        assertTrue(engine.actionsAt(play()).isEmpty(), "no decisions after the end")
    }

    @Test
    fun nothingKeepsWhatIsUnderWay() {
        val t = play("A-master")
        val second = t.nodes.getValue("A-master/NOTHING").current
        assertEquals("MASTER", second.person.facts[FactKey.EDUCATION], "the master's ends although the player does nothing")
    }

    @Test
    fun theSameDecisionAtTheSameMomentGivesTheSameChild() {
        val a = engine.decide(start(), "", "A-master")
        val (again, key) = engine.decide(a.first, "", "A-master")
        assertEquals(a.second, key)
        assertEquals(a.first, again, "reused, not recomputed")
        assertEquals(play("A-master").current, play("A-master").current)
    }

    @Test
    fun goingBackOpensANewBranchAndForgetsNothing() {
        val lived = play("A-master")
        val back = engine.choose(lived.goTo(""), "A-start-working")
        assertEquals("A-start-working", back.cursor)
        assertTrue(lived.cursor in back.nodes, "the life already lived is kept")
        assertEquals(setOf("A-master", "A-start-working"), back.children("").toSet())
        // Coming back to the start remembers the last way down.
        assertEquals("A-start-working", back.goTo("").lastChild[""])
    }

    @Test
    fun sameLevelLuckIsSharedBySiblingsFullRandomIsNot() {
        fun lucks(mode: LuckMode) = listOf("A-master", "A-start-working", NOTHING).map { id ->
            engine.decide(start(mode), "", id).first.nodes.getValue(id).turns[0].luck
        }
        assertEquals(1, lucks(LuckMode.SAME_LEVEL).toSet().size)
        assertEquals(3, lucks(LuckMode.FULL_RANDOM).toSet().size)
    }

    @Test
    fun differentDecisionsEndDifferently() {
        val all = engine.explore(start())
        assertTrue(all.lives.size >= 5)
        assertNotEquals(1, all.lives.map { all.nodes.getValue(it).current }.toSet().size)
        all.lives.forEach { key -> assertEquals(key, all.nodes.getValue(key).nodeId) }
        assertEquals("", all.cursor, "exploring does not move the user")
    }

    @Test
    fun anUnavailableActionIsRefused() {
        assertFailsWith<IllegalArgumentException> { engine.decide(start(), "", "A-loan") }
    }
}
