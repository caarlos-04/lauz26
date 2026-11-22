package com.lauzhack.twin.content

import com.lauzhack.twin.chronicle.ChronicleTemplates
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.EventCatalog
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FlowModel
import com.lauzhack.twin.model.MacroFile
import com.lauzhack.twin.resources.Res
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

// Everything the app reads at start, decoded from the JSON packaged as Compose resources (composeResources/files/,
// copied from content/ by tools/ship_resources.py). Same files on Android, Desktop and Web.
data class AppContent(
    val events: EventCatalog,
    val facts: FactCatalog,
    val assumptions: Assumptions,
    val chronicle: ChronicleTemplates,
    val flowModel: FlowModel,
    val macro: MacroFile,
) {
    companion object {
        // The generated files carry a `meta` block (and macro.json its public series) that the model does not decode.
        val json = Json { ignoreUnknownKeys = true }

        @OptIn(ExperimentalResourceApi::class)
        suspend fun read(name: String): String = Res.readBytes("files/$name").decodeToString()

        suspend fun load(): AppContent = AppContent(
            events = json.decodeFromString(read("events.json")),
            facts = json.decodeFromString(read("facts.json")),
            assumptions = json.decodeFromString(read("assumptions.json")),
            chronicle = json.decodeFromString(read("chronicle.json")),
            flowModel = json.decodeFromString(read("flow-model.json")),
            macro = json.decodeFromString(read("macro.json")),
        )
    }
}
