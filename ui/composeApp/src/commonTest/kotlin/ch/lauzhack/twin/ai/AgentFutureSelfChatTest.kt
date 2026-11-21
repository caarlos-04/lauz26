package ch.lauzhack.twin.ai

import ch.lauzhack.twin.engine.PathKind
import ch.lauzhack.twin.fake.CannedFutureSelfChat
import ch.lauzhack.twin.fake.FakeData
import ch.lauzhack.twin.fake.FakeGameEngine
import ch.lauzhack.twin.model.ChatContext
import ch.lauzhack.twin.model.ChatMessage
import ch.lauzhack.twin.model.ChatRole
import ch.lauzhack.twin.model.GameConfig
import ch.lauzhack.twin.model.GameSession
import ch.lauzhack.twin.model.Lang
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentFutureSelfChatTest {
    private val engine = FakeGameEngine(FakeData.events, FakeData.assumptions)
    private val mapper = BriefingMapper(
        eventById = { id -> FakeData.events.firstOrNull { it.id == id } },
        text = { key -> "<$key>" }, // no Compose resources in unit tests
    )

    /** Plays always option A with the engine's luck, like PathReplayTest. */
    private fun context(editedTurns: Set<Int> = emptySet()): ChatContext {
        var s = GameSession(FakeData.alexiaProfile, FakeData.alexiaState, 11L, emptyList(), GameConfig())
        while (true) {
            val event = engine.nextEvent(s) ?: break
            val option = event.options.first()
            s = s.copy(turns = s.turns + engine.playTurn(s, event, option.id, engine.drawCase(option, s.seed, s.turns.size)))
        }
        return ChatContext(
            session = s,
            stats = engine.stats(s, PathKind.CHOSEN),
            comparison = engine.compare(s),
            simulation = engine.simulate(s, PathKind.CHOSEN, nPaths = 50),
            assumptions = FakeData.assumptions,
            language = Lang.ES,
            editedTurns = editedTurns,
        )
    }

    @Test
    fun mapsEveryTurnAndTheEngineResults() = runTest {
        val ctx = context()
        val b = mapper.map(ctx)
        assertEquals(ctx.session.turns.size, b.decisions.size)
        assertEquals("<event_studies_title>", b.decisions.first().question)
        assertEquals(ctx.stats.finalNetWorth, b.finalNetWorthChf)
        assertEquals(ctx.comparison!!.alternative.finalNetWorth, b.otherPath!!.finalNetWorthChf)
        assertEquals(ctx.session.profile.goals.size, b.goals.size)
        assertEquals(ChatLanguage.ES, b.language)
        assertFalse(b.isWhatIf)
    }

    @Test
    fun editedTurnsMakeAWhatIfBriefing() = runTest {
        val b = mapper.map(context(editedTurns = setOf(1)))
        assertTrue(b.isWhatIf)
        assertTrue(b.decisions[1].editedInWhatIf)
        assertFalse(b.decisions[0].editedInWhatIf)
    }

    @Test
    fun passesBriefingAndHistoryToTheAgent() = runTest {
        var seenHistory: List<ChatTurn>? = null
        val chat = AgentFutureSelfChat({ _, h, _ -> seenHistory = h; "hola" }, mapper, CannedFutureSelfChat())
        val history = listOf(
            ChatMessage(ChatRole.USER, "q1"),
            ChatMessage(ChatRole.FUTURE_SELF, "a1"),
            ChatMessage(ChatRole.FUTURE_SELF, "canned", offline = true),
        )
        val answer = chat.ask(context(), history, "q2")
        assertEquals(ChatMessage(ChatRole.FUTURE_SELF, "hola"), answer)
        // Offline (canned) answers are not sent to the agent as its own words.
        assertEquals(listOf(ChatTurn(true, "q1"), ChatTurn(false, "a1")), seenHistory)
    }

    @Test
    fun fallsBackToCannedAnswerWhenTheAgentFails() = runTest {
        val chat = AgentFutureSelfChat({ _, _, _ -> error("no network") }, mapper, CannedFutureSelfChat())
        val answer = chat.ask(context(), emptyList(), "riesgo")
        assertTrue(answer.offline)
        assertEquals(ChatRole.FUTURE_SELF, answer.role)
        assertTrue(answer.text.isNotBlank())
    }
}
