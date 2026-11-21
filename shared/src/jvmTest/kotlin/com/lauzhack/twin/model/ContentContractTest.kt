package com.lauzhack.twin.model

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Checks that the JSON written by Dev C (content/, tools/out/) decodes into the shared model classes.
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

    @Test
    fun generatedFilesDecodeWhenPresent() {
        // tools/out/ is git-ignored: run `python tools/calibrate.py` to generate it.
        val out = File(repoRoot, "tools/out")
        File(out, "profiles-sample.json").takeIf { it.exists() }?.let { file ->
            val sample = json.decodeFromString<ProfilesSample>(file.readText())
            assertTrue(sample.profiles.any { it.profile.id == "P00001" })
        }
        File(out, "calibration.json").takeIf { it.exists() }?.let { file ->
            assertTrue(json.decodeFromString<CalibrationFile>(file.readText()).segments.isNotEmpty())
        }
    }
}
