package com.lauzhack.twin.chronicle

import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.EventCatalog
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
        events: EventCatalog,
        facts: FactCatalog,
        startYear: Int = START_YEAR,
    ): List<ChronicleLine> {
        val byId = events.events.associateBy { it.id }
        val chosen = mutableMapOf<String, String>()     // eventId -> option of its DECISION beat
        var current: String? = null                     // event of the decision being told
        val lines = mutableListOf<ChronicleLine>()
        for (b in beats) {
            val d = b.data
            val ev = b.eventId?.let { byId[it] }
            val v = mutableMapOf(
                "age" to b.age.toString(),
                "year" to (startYear + b.month / 12).toString(),
                "years" to (b.month / 12).toString(),
            )
            for (k in MONEY) d[k]?.toDoubleOrNull()?.let { v[k] = chf(it, lang) }
            d["cash"]?.toDoubleOrNull()?.let { v["cashAbs"] = chf(abs(it), lang) }
            d["goal"]?.let { goal -> v["goal"] = templates.labels.goal[goal]?.get(lang) ?: goal }

            if (b.kind == BeatKind.DECISION || b.kind == BeatKind.QUIET_PERIOD) current = ev?.id
            if (b.kind == BeatKind.DECISION && ev != null) chosen[ev.id] = d["option"].orEmpty()
            v["event"] = ev?.title?.get(lang).orEmpty()
            // The cause is told only when it is not the decision of this paragraph (e.g. the job after the master's).
            v["because"] = if (ev != null && ev.id != current) fill(templates.because.getValue(lang), v) else ""
            val option = ev?.options?.firstOrNull { it.id == chosen[ev.id] }
            if (option != null) {
                v["option"] = option.label[lang].orEmpty()
                d["case"]?.let { case -> option.cases.entries.firstOrNull { it.key.name == case }?.let { v["narrative"] = it.value.narrative[lang].orEmpty() } }
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
