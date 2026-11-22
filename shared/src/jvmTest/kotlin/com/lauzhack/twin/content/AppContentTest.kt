package com.lauzhack.twin.content

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The app's data is packaged as resources and decodes into the shared model, through the same path the app uses.
class AppContentTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "content").isDirectory }

    @Test
    fun everyFileLoadsFromTheResources() = runBlocking {
        val c = AppContent.load()
        assertTrue(c.events.events.isNotEmpty())
        assertTrue(c.facts.facts.isNotEmpty())
        assertTrue(c.assumptions.sensitivity.isNotEmpty())
        assertTrue(c.chronicle.beats.isNotEmpty())
        assertTrue(c.flowModel.flows.isNotEmpty())
        assertEquals(11, c.interview.questions.size)
        assertEquals("Alexia", c.alexia.statement.person.displayName)
        // the sensitivity range of inflation is the one of macro.json (tools/assumptions.py copies it)
        val inflation = c.macro.trends.priceInflationAnnual
        val sens = c.assumptions.sensitivity.first { it.id == "price_inflation_annual" }
        assertEquals(Triple(inflation.value, inflation.low, inflation.high), Triple(sens.base, sens.low, sens.high))
    }

    @Test
    fun shippedCopiesAreIdenticalToContent() = runBlocking {
        // tools/ship_resources.py copies content/*.json; a stale copy would make the app run on old data.
        val sources = File(repoRoot, "content").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(sources.map { it.name }.containsAll(listOf("flow-model.json", "macro.json", "events.json", "facts.json")))
        for (f in sources) {
            assertEquals(f.readText(), AppContent.read(f.name), "${f.name}: run python tools/ship_resources.py")
        }
    }
}
