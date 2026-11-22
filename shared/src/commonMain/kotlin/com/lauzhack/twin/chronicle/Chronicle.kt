package com.lauzhack.twin.chronicle

import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.engine.outcomeText
import com.lauzhack.twin.model.ActionCatalog
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.LocalizedText
import com.lauzhack.twin.model.StoryBeat
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

// Root of content/chronicle.json: one sentence template per StoryBeat, ES/EN (P11, plan B without network).
@Serializable
data class ChronicleTemplates(
    val version: Int,
    val beats: Map<BeatKind, LocalizedText>,        // every kind except LIFE_CHANGE
    val lifeChange: LifeChangeTemplates,
    val because: LocalizedText,
    val labels: ChronicleLabels,
)

@Serializable
data class LifeChangeTemplates(
    val employment: LocalizedText,
    val household: LocalizedText,
    val fact: Map<FactKey, LocalizedText>,
    val flag: LocalizedText,
)

@Serializable
data class ChronicleLabels(
    val employment: Map<Employment, LocalizedText>,
    val household: Map<Household, LocalizedText>,
    val goal: Map<String, LocalizedText>,           // dataset goal_name -> how the story calls it
)

data class ChronicleLine(val month: Int, val age: Int, val kind: BeatKind, val text: String)

/**
 * Tells the life line (StoryBeats) as text with the templates. Pure function; it only uses the numbers the beats carry.
 * Same rules as tools/chronicle.py (tools/test_chronicle.py and ChronicleTest use the same beats).
 */
object Chronicle {
    const val START_YEAR = 2025
    private val PARAGRAPH_STARTS = setOf(BeatKind.DECISION, BeatKind.QUIET_PERIOD, BeatKind.END)
    private val MONEY = listOf("cash", "target", "netWorth")
    private val PLACEHOLDER = Regex("""\{(\w+)\}""")

    fun render(
        beats: List<StoryBeat>,
        lang: Lang,
        templates: ChronicleTemplates,
        events: ActionCatalog,
        facts: FactCatalog,
        startYear: Int = START_YEAR,
    ): List<ChronicleLine> {
        val titles = events.actions.associate { it.id to it.title } + events.shocks.associate { it.id to it.title }
        val outcomes = events.actions.associate { it.id to (it.outcome to it.uncertain) } + events.shocks.associate { it.id to (it.outcome to it.uncertain) }
        var current: String? = null                     // action of the decision being told
        val lines = mutableListOf<ChronicleLine>()
        for (b in beats) {
            val d = b.data
            val ev = b.eventId?.takeIf { it in titles }
            val v = mutableMapOf(
                "age" to b.age.toString(),
                "year" to (startYear + b.month / 12).toString(),
                "years" to (b.month / 12).toString(),
            )
            for (k in MONEY) d[k]?.toDoubleOrNull()?.let { v[k] = chf(it, lang) }
            d["cash"]?.toDoubleOrNull()?.let { v["cashAbs"] = chf(abs(it), lang) }
            d["goal"]?.let { goal -> v["goal"] = templates.labels.goal[goal]?.get(lang) ?: goal }

            if (b.kind == BeatKind.DECISION || b.kind == BeatKind.QUIET_PERIOD) current = ev
            v["event"] = ev?.let { titles.getValue(it)[lang] }.orEmpty()
            // The cause is told only when it is not the decision of this paragraph (e.g. the job after the master's).
            v["because"] = if (ev != null && ev != current) fill(templates.because.getValue(lang), v) else ""
            if (ev != null && (b.kind == BeatKind.OUTCOME || b.kind == BeatKind.SHOCK)) {
                val (outcome, uncertain) = outcomes.getValue(ev)
                val drawn = uncertain.mapNotNull { u -> d[u.name]?.toDoubleOrNull()?.let { u.name to it } }.toMap()
                v["narrative"] = outcomeText(outcome, uncertain, drawn, lang)
            }

            val template: String = if (b.kind == BeatKind.LIFE_CHANGE) {
                val lc = templates.lifeChange
                when {
                    "employment" in d -> {
                        v["employment"] = labelOf(templates.labels.employment, d.getValue("employment"), lang)
                        lc.employment.getValue(lang)
                    }
                    "household" in d -> {
                        v["household"] = labelOf(templates.labels.household, d.getValue("household"), lang)
                        lc.household.getValue(lang)
                    }
                    "fact" in d -> {
                        val key = FactKey.entries.firstOrNull { it.name == d["fact"] } ?: continue
                        v["value"] = facts.facts[key]?.get(d["value"])?.get(lang) ?: d["value"].orEmpty()
                        lc.fact[key]?.get(lang) ?: continue
                    }
                    "flag" in d -> {
                        v["flag"] = facts.flags[d["flag"]]?.get(lang) ?: d.getValue("flag")
                        lc.flag.getValue(lang)
                    }
                    else -> continue
                }
            } else {
                templates.beats[b.kind]?.get(lang) ?: continue
            }
            lines += ChronicleLine(b.month, b.age, b.kind, fill(template, v))
        }
        return lines
    }

    /** A new paragraph at every decision, quiet period and at the end. */
    fun paragraphs(lines: List<ChronicleLine>): List<String> {
        val out = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (out.isEmpty() || line.kind in PARAGRAPH_STARTS) out += mutableListOf<String>()
            out.last() += line.text
        }
        return out.map { it.joinToString(" ") }
    }

    /** -5120 -> EN "CHF -5’120", ES "-5’120 CHF" (Swiss grouping; currency placed as in events.json). */
    fun chf(x: Double, lang: Lang): String {
        val n = x.roundToLong()
        val digits = abs(n).toString().reversed().chunked(3).joinToString("’").reversed()
        val number = (if (n < 0) "-" else "") + digits
        return if (lang == Lang.ES) "$number CHF" else "CHF $number"
    }

    // Unknown placeholders stay visible instead of crashing (like keyString in the UI).
    private fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: m.value }

    private inline fun <reified E : Enum<E>> labelOf(labels: Map<E, LocalizedText>, code: String, lang: Lang): String =
        enumValues<E>().firstOrNull { it.name == code }?.let { labels[it]?.get(lang) } ?: code
}
