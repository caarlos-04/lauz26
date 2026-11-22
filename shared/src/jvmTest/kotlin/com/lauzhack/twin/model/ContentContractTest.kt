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
    fun actionsDecode() {
        val catalog = json.decodeFromString<ActionCatalog>(File(repoRoot, "content/events.json").readText())
        assertTrue(catalog.actions.any { it.id == NOTHING }, "carry on as you are")
        assertTrue(catalog.shocks.isNotEmpty())
        for ((id, effects, uncertain) in catalog.actions.map { Triple(it.id, it.effects, it.uncertain) } +
                                        catalog.shocks.map { Triple(it.id, it.effects, it.uncertain) }) {
            for (u in uncertain) {
                assertTrue(u.low.q < 0.5 && 0.5 < u.high.q && u.low.value <= u.median && u.median <= u.high.value, "$id ${u.name}")
                u.targets.forEach { assertTrue(it.effect in effects.indices, "$id ${u.name}") }
            }
        }
    }

    @Test
    fun factsDecode() {
        val facts = json.decodeFromString<FactCatalog>(File(repoRoot, "content/facts.json").readText())
        assertEquals(FactKey.entries.toSet(), facts.facts.keys)
    }
}
