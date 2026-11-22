package com.lauzhack.twin.ai

import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.fake.CannedFutureSelfChat
import com.lauzhack.twin.fake.FakeData
import com.lauzhack.twin.fake.FakeTwinFactory
import com.lauzhack.twin.model.ChatMessage
import com.lauzhack.twin.model.ChatRole
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.Persona
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentFutureSelfChatTest {
    private val engine = FakeData.engine()
    private val mapper = BriefingMapper(
        catalog = FakeData.events,
        text = { key -> "<$key>" }, // no Compose resources in unit tests
    )
    private val canned = CannedFutureSelfChat(latencyMs = 0)

    /** Alexia does a master's and then carries on; the engine's luck; then the game is finished. */
    private fun context(whatIfTurns: Set<Int> = emptySet()): ChatContext {
        val game = FakeTwinFactory().demo()
        var s = GameSession(game.opening, 11L, game.preferences, emptyList(), GameConfig())
        for (id in listOf("A-master", "NOTHING", "NOTHING", "NOTHING", "NOTHING")) s = s.copy(turns = s.turns + engine.decide(s, id))
        return ChatContext(engine.finish(s), FakeData.facts, FakeData.assumptions, Lang.ES, whatIfTurns)
    }

    @Test
    fun mapsEveryTurnAndTheEngineResults() = runTest {
        val ctx = context()
        val b = mapper.map(ctx)
        assertEquals(ctx.result.session.turns.size, b.turns.size)
        assertEquals("Hacer un máster", b.turns.first().decision)         // LocalizedText in the chat language
        assertTrue(b.turns[1].quiet)
        assertEquals(ctx.result.stats.finalNetWorth, b.end.netWorthChf, 1e-6)
        assertEquals(ctx.result.session.initial.goals.size, b.goals.size)
        assertEquals("<Emergency savings>", b.goals.first().name)
        assertTrue(b.lifeAtEnd.any { it == "Máster" })                   // fact label from the catalogue
        assertTrue(b.moments.isNotEmpty())
        assertEquals(ChatLanguage.ES, b.language)
        assertFalse(b.isWhatIf)
    }

    @Test
    fun whatIfTurnsMakeAWhatIfBriefing() = runTest {
        val b = mapper.map(context(whatIfTurns = setOf(1)))
        assertTrue(b.isWhatIf)
        assertTrue(b.turns[1].editedInWhatIf)
        assertFalse(b.turns[0].editedInWhatIf)
    }

    @Test
    fun passesPersonaPromptAndHistoryToTheAgent() = runTest {
        var seenSystem = ""
        var seenHistory: List<ChatTurn>? = null
        val offlineMessage = ChatMessage(ChatRole.TWIN, "canned")
        val chat = AgentFutureSelfChat(
            generator = { system, h, _ -> seenSystem = system; seenHistory = h; "hola" },
            mapper = mapper,
            fallback = canned,
            offlineHistory = { it === offlineMessage },
        )
        val history = listOf(ChatMessage(ChatRole.USER, "q1"), ChatMessage(ChatRole.TWIN, "a1"), offlineMessage)
        val answer = chat.ask(context(), Persona.FUTURE_SELF, history, "q2")
        assertEquals(ChatMessage(ChatRole.TWIN, "hola"), answer)
        assertFalse(chat.lastAnswerOffline)
        assertContains(seenSystem, "future self")
        assertEquals(listOf(ChatTurn(true, "q1"), ChatTurn(false, "a1")), seenHistory)
    }

    @Test
    fun fallsBackToCannedAnswersWhenTheAgentFails() = runTest {
        val chat = AgentFutureSelfChat({ _, _, _ -> error("no network") }, mapper, canned)
        val answer = chat.ask(context(), Persona.FUTURE_SELF, emptyList(), "riesgo")
        assertTrue(chat.lastAnswerOffline)
        assertEquals(ChatRole.TWIN, answer.role)
        assertTrue(answer.text.isNotBlank())
        assertTrue(chat.chronicle(context()).isNotBlank())
        assertTrue(chat.lastAnswerOffline)
    }
}
