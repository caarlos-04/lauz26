package com.lauzhack.twin.model

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Checks that the content JSON written by Dev C (content/) decodes into the shared model classes.
// Generated files (tools/out/) are checked in SimulationContractTest.
class ContentContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Gradle runs tests with the module dir (shared/) as working directory.
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "content").isDirectory }

    @Test
    fun eventsDecode() {
        val catalog = json.decodeFromString<EventCatalog>(File(repoRoot, "content/events.json").readText())
        assertTrue(catalog.events.isNotEmpty())
        for (event in catalog.events) {
            assertEquals(listOf("A", "B"), event.options.map { it.id }, event.id)
            for (option in event.options) {
                assertEquals(CaseType.entries.toSet(), option.cases.keys, "${event.id}/${option.id}")
                assertEquals(1.0, option.cases.values.sumOf { it.prob }, 1e-9, "${event.id}/${option.id}")
            }
        }
    }

    @Test
    fun factsDecode() {
        val facts = json.decodeFromString<FactCatalog>(File(repoRoot, "content/facts.json").readText())
        assertEquals(FactKey.entries.toSet(), facts.facts.keys)
    }
}
