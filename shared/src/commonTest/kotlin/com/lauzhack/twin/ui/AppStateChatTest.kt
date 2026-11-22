package com.lauzhack.twin.ui

import com.lauzhack.twin.ai.ChatContext
import com.lauzhack.twin.ai.FutureSelfChat
import com.lauzhack.twin.fake.FakeData
import com.lauzhack.twin.fake.FakeTwinFactory
import com.lauzhack.twin.model.ChatMessage
import com.lauzhack.twin.model.ChatRole
import com.lauzhack.twin.model.NOTHING
import com.lauzhack.twin.model.Persona
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat with your self of a moment (F3): what it knows and where its conversation lives. */
class AppStateChatTest {

    /** Records the context of every question and answers with the number of turns it knows. */
    private class RecordingChat : FutureSelfChat {
        val contexts = mutableListOf<ChatContext>()
        override suspend fun ask(context: ChatContext, persona: Persona, history: List<ChatMessage>, question: String): ChatMessage {
            contexts += context
            return ChatMessage(ChatRole.TWIN, "turns=${context.result.session.turns.size}")
        }
        override suspend fun chronicle(context: ChatContext): String = "story of ${context.result.session.turns.size} turns"
    }

    private fun state(chat: FutureSelfChat) = AppState(
        engine = FakeData.engine(),
        twins = FakeTwinFactory(),
        chat = chat,
        assumptions = FakeData.assumptions,
        facts = FakeData.facts,
        catalog = Catalog(FakeData.events),
        interview = FakeData.interview,
    )

    @Test
    fun noChatBeforeTheFirstDecision() {
        val s = state(RecordingChat())
        s.startDemo()
        assertFalse(s.canChat)
        s.openChat()
        assertEquals(Screen.DILEMMA, s.screen)
    }

    @Test
    fun theSelfOfAMomentOnlyKnowsTheLifeUpToIt() = runTest {
        val chat = RecordingChat()
        val s = state(chat)
        s.startDemo()
        s.choose("A-master")
        s.openChat()
        assertEquals(Screen.CHAT, s.screen)
        s.ask("How are you?")
        val ctx = chat.contexts.single()
        assertEquals(1, ctx.result.session.turns.size)
        assertNull(ctx.noiseBand)
        s.closeChat()
        assertEquals(Screen.RESOLUTION, s.screen)
    }

    @Test
    fun eachMomentKeepsItsOwnConversation() = runTest {
        val s = state(RecordingChat())
        s.startDemo()
        s.choose("A-master")
        s.openChat()
        s.ask("Hi from the master's")
        assertEquals(2, s.chatHistory.size)
        s.closeChat()

        s.decideAgain()                                // back to the start: another decision there
        assertEquals(Screen.DILEMMA, s.screen)
        assertEquals("", s.tree?.cursor)
        assertTrue("A-master" in s.explored)
        s.choose("A-start-working")
        s.openChat()
        assertEquals("A-start-working", s.tree?.cursor)
        assertTrue(s.chatHistory.isEmpty())

        s.chatAt("A-master")
        assertEquals(listOf("Hi from the master's", "turns=1"), s.chatHistory.map { it.message.text })
    }

    @Test
    fun aCompleteLifeKnowsItsWholeStory() = runTest {
        val chat = RecordingChat()
        val s = state(chat)
        s.startDemo()
        while (s.screen != Screen.RESULTS) if (s.screen == Screen.DILEMMA) s.choose(NOTHING) else s.nextTurn()
        s.openChat()
        s.ask("How did it end?")
        assertEquals(5, chat.contexts.single().result.session.turns.size)
        assertTrue(chat.contexts.single().noiseBand != null)
    }

    @Test
    fun goingBackToAnEarlierDecision() {
        val s = state(RecordingChat())
        s.startDemo()
        while (s.screen != Screen.RESULTS) if (s.screen == Screen.DILEMMA) s.choose(NOTHING) else s.nextTurn()
        s.switchAt(2)                                  // the third decision of this life
        assertEquals(Screen.DILEMMA, s.screen)
        assertEquals("NOTHING/NOTHING", s.tree?.cursor)
        assertTrue(NOTHING in s.explored)
        assertTrue(s.available.isNotEmpty())
    }

    /** Counts the engine's heavy calls, to check they are cached per node. */
    private class CountingEngine(private val e: com.lauzhack.twin.engine.GameEngine) : com.lauzhack.twin.engine.GameEngine by e {
        var finishes = 0
        var bands = 0
        override fun finish(session: com.lauzhack.twin.model.GameSession) = e.finish(session).also { finishes++ }
        override fun noiseBand(session: com.lauzhack.twin.model.GameSession) = e.noiseBand(session).also { bands++ }
    }

    @Test
    fun resultsAndNoiseBandAreComputedOncePerNode() = runTest {
        val engine = CountingEngine(FakeData.engine())
        val s = AppState(engine, FakeTwinFactory(), RecordingChat(), FakeData.assumptions, FakeData.facts, Catalog(FakeData.events), FakeData.interview)
        s.startDemo()
        while (s.screen != Screen.RESULTS) if (s.screen == Screen.DILEMMA) s.choose(NOTHING) else s.nextTurn()
        assertNull(s.results)                         // nothing heavy runs until the screen asks for it
        repeat(3) { s.prepareResults(); s.prepareNoiseBand() }
        s.ask("How did it end?")                      // the chat reuses the same results and band
        assertTrue(s.results != null)
        assertEquals(1, engine.finishes)
        assertEquals(1, engine.bands)
    }
}
