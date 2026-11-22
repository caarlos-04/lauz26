package com.lauzhack.twin.chronicle

import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.EventCatalog
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.Household
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.StoryBeat
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// content/chronicle.json decodes and tells the life line. Same beats as tools/test_chronicle.py: keep both in sync.
class ChronicleTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "content").isDirectory }
    private fun content(name: String) = File(repoRoot, "content/$name").readText()

    private val templates = json.decodeFromString<ChronicleTemplates>(content("chronicle.json"))
    private val events = json.decodeFromString<EventCatalog>(content("events.json"))
    private val facts = json.decodeFromString<FactCatalog>(content("facts.json"))

    private fun b(month: Int, age: Int, kind: BeatKind, event: String? = null, vararg data: Pair<String, Any>) =
        StoryBeat(month, age, kind, event, data.associate { (k, v) -> k to v.toString() })

    private val beats = listOf(
        b(0, 20, BeatKind.START, null, "cash" to 8000),
        b(0, 20, BeatKind.DECISION, "E-master", "option" to "A", "label" to "ignored: texts come from events.json"),
        b(0, 20, BeatKind.CASE_RESOLVED, "E-master", "case" to "NEUTRAL", "luck" to "0.50"),
        b(0, 20, BeatKind.LIFE_CHANGE, "E-master", "fact" to "EDUCATION", "value" to "MASTER_IN_PROGRESS"),
        b(14, 21, BeatKind.CASH_NEGATIVE, null, "cash" to -995),
        b(24, 22, BeatKind.QUIET_PERIOD),
        b(25, 22, BeatKind.LIFE_CHANGE, "E-master", "employment" to "EMPLOYED"),
        b(25, 22, BeatKind.LIFE_CHANGE, "E-master", "flag" to "did_master"),
        b(61, 25, BeatKind.GOAL_REACHED, null, "goal" to "Career transition", "target" to 39366),
        b(120, 30, BeatKind.END, null, "netWorth" to 158098, "cash" to 158098),
    )

    private fun lines(lang: Lang) = Chronicle.render(beats, lang, templates, events, facts).map { it.text }

    @Test
    fun templatesCoverEveryBeatFactAndLabelInBothLanguages() {
        for (kind in BeatKind.entries.filter { it != BeatKind.LIFE_CHANGE }) {
            val t = templates.beats[kind]
            assertTrue(t != null && Lang.entries.all { !t[it].isNullOrBlank() }, "beats.$kind")
        }
        assertEquals(FactKey.entries.toSet(), templates.lifeChange.fact.keys)
        assertEquals(Employment.entries.toSet(), templates.labels.employment.keys)
        assertEquals(Household.entries.toSet(), templates.labels.household.keys)
    }

    @Test
    fun english() {
        val narrative = events.events.first { it.id == "E-master" }.options.first { it.id == "A" }.cases.getValue(CaseType.NEUTRAL).narrative.getValue(Lang.EN)
        assertEquals(
            listOf(
                "You start at 20, in 2025, with CHF 8’000 in your account.",
                "At 20 (2025) a decision came up, “Master's degree or a job?”, and you chose: Do the master's.",
                narrative,
                "At 20 your studies reached a new stage: Studying for a master's.",
                "At 21 your account went into the red (CHF 995 below zero) and you started paying interest on it.",
                "At 22 two quiet years began, with no big decisions.",
                "At 22 you started working full time (because of “Master's degree or a job?”).",
                "Milestone at 22: Did a master's (because of “Master's degree or a job?”).",
                "At 25 you reached your goal: a career change (CHF 39’366).",
                "10 years later, at 30, your net worth is CHF 158’098, CHF 158’098 of it in cash.",
            ),
            lines(Lang.EN),
        )
    }

    @Test
    fun spanishMoneyAndParagraphs() {
        val es = lines(Lang.ES)
        assertEquals("A los 21 años tu cuenta quedó en números rojos (995 CHF por debajo de cero) y empezaste a pagar intereses.", es[4])
        assertEquals("10 años después, con 30 años, tu patrimonio es de 158’098 CHF, de los que 158’098 CHF están en efectivo.", es.last())
        assertEquals(4, Chronicle.paragraphs(Chronicle.render(beats, Lang.ES, templates, events, facts)).size)
    }

    @Test
    fun unknownCodesNeverCrash() {
        val out = Chronicle.render(
            listOf(b(0, 20, BeatKind.LIFE_CHANGE, "E-nope", "flag" to "new_flag"), b(1, 20, BeatKind.GOAL_MISSED, null, "goal" to "Some goal", "target" to 1)),
            Lang.EN, templates, events, facts,
        )
        assertEquals("Milestone at 20: new_flag.", out[0].text)
        assertTrue("Some goal" in out[1].text)
    }

    @Test
    fun moneyFormat() {
        assertEquals("CHF -5’120", Chronicle.chf(-5120.0, Lang.EN))
        assertEquals("1’234’567 CHF", Chronicle.chf(1234567.0, Lang.ES))
        assertEquals("CHF 0", Chronicle.chf(0.0, Lang.EN))
    }
}
