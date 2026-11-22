package com.lauzhack.twin.ai

import com.lauzhack.twin.content.AppContent
import com.lauzhack.twin.engine.PortableRandom
import com.lauzhack.twin.engine.RealGameEngine
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.fake.CannedFutureSelfChat
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.Lang
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

// #18 point 1: without network the chronicle tells the real life line with content/chronicle.json.
class OfflineChronicleTest {

    private val content = runBlocking { AppContent.load() }
    private val engine = RealGameEngine.from(content.flowModel, content.macro, content.events, content.assumptions)

    private fun result() = run {
        var s = GameSession(content.alexia.statement, 2026L, content.alexia.preferences, emptyList(), GameConfig())
        for (id in listOf("A-master", "NOTHING", "NOTHING", "NOTHING", "NOTHING")) s = s.copy(turns = s.turns + engine.decide(s, id))
        engine.finish(s)
    }

    @Test
    fun tellsTheLifeLineInTheUserLanguage() = runBlocking {
        val chat = CannedFutureSelfChat(latencyMs = 0, templates = content.chronicle, events = content.events)
        val r = result()
        val es = chat.chronicle(ChatContext(r, content.facts, content.assumptions, Lang.ES))
        assertTrue(es.startsWith("Empiezas con 20 años, en 2025, con 8’000 CHF en tu cuenta."), es)
        assertTrue("decidiste: Hacer un máster" in es, es)
        assertTrue(es.trimEnd().endsWith("no una predicción."), es)
        val en = chat.chronicle(ChatContext(r, content.facts, content.assumptions, Lang.EN))
        assertTrue(en.startsWith("You start at 20, in 2025, with CHF 8’000 in your account."), en)
        assertTrue("years later" in en, en)
    }

    @Test
    fun withoutTemplatesTheSummaryIsKept() = runBlocking {
        val text = CannedFutureSelfChat(latencyMs = 0).chronicle(ChatContext(result(), content.facts, content.assumptions, Lang.EN))
        assertTrue(text.startsWith("Over ten years I took"), text)
    }
}
