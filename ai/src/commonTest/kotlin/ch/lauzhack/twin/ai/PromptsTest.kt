package ch.lauzhack.twin.ai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptsTest {

    private fun briefing(whatIf: Boolean = false, lang: ChatLanguage = ChatLanguage.EN) = LifeBriefing(
        language = lang,
        profile = ProfileLine("Alexia", 22, 2025, 10, "Lausanne", "student", "employed", "single"),
        start = MoneySnapshot(0, 8_000.0, 8_000.0, 350.0, 0.0),
        finalNetWorthChf = 61_234.4,
        decisions = listOf(
            DecisionLine(1, 0, "Finish the master?", "Finish it", "Start working", Luck.GOOD, "You got a grant.", 8_000.0, 12_500.0, 9_000.0),
            DecisionLine(2, 24, "Move to a shared flat?", "Stay", "Move", Luck.BAD, "Rent went up.", 12_500.0, 10_100.0, 4_200.0, editedInWhatIf = whatIf),
        ),
        goals = listOf(GoalLine("Emergency fund", 10_000.0, 36, reached = true, probability = 0.72)),
        risk = RiskLine(-1_250.0, 30, 3),
        outlook = OutlookLine(500, 20_000.0, 55_000.0, 90_000.0, 15_000.0, 0.18),
        otherPath = OtherPathLine(48_000.0, 13_234.4, 1, 0, listOf("Finish the master?")),
        assumptionNotes = listOf("Inflation 1.5 % per year (assumption)."),
        isWhatIf = whatIf,
    )

    @Test
    fun chfFormattingIsStable() {
        assertEquals("61'234 CHF", Prompts.chf(61_234.4))
        assertEquals("-1'250 CHF", Prompts.chf(-1_250.0))
        assertEquals("+13'234 CHF", Prompts.chf(13_234.4, signed = true))
        assertEquals("18 %", Prompts.pct(0.18))
    }

    @Test
    fun briefingContainsEveryDecisionAndTheEngineNumbers() {
        val text = Prompts.renderBriefing(briefing())
        listOf("Finish the master?", "Move to a shared flat?", "Finish it", "Start working", "Rent went up.").forEach {
            assertContains(text, it)
        }
        assertContains(text, "good luck")
        assertContains(text, "bad luck")
        assertContains(text, "2027, age 24")              // turn 2 happened at month 24
        assertContains(text, "net worth 61'234 CHF")       // final net worth from the engine
        assertContains(text, "-1'250 CHF")                 // lowest cash
        assertContains(text, "Emergency fund: 10'000 CHF by 2028 -> REACHED, reached in 72 %")
        assertContains(text, "+13'234 CHF")                // delta vs the other path
        assertContains(text, "Inflation 1.5 %")
    }

    @Test
    fun whatIfBranchIsFlagged() {
        val played = Prompts.renderBriefing(briefing(whatIf = false))
        val edited = Prompts.renderBriefing(briefing(whatIf = true))
        assertFalse("WHAT-IF" in played)
        assertContains(edited, "WHAT-IF")
        assertContains(edited, "[changed in the what-if tree]")
    }

    @Test
    fun systemPromptHasRulesAndLanguage() {
        val system = Prompts.systemFor(briefing(lang = ChatLanguage.ES))
        assertTrue(system.startsWith(Prompts.SYSTEM_PROMPT))
        assertContains(system, "Never calculate new numbers")
        assertContains(system, "not financial advice")
        assertContains(system, "Answer language: Spanish")
    }

    @Test
    fun briefingIsDeterministic() {
        assertEquals(Prompts.renderBriefing(briefing()), Prompts.renderBriefing(briefing()))
    }
}
