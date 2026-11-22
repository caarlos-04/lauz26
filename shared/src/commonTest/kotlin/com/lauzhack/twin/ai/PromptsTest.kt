package com.lauzhack.twin.ai

import com.lauzhack.twin.model.Persona
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptsTest {

    private fun briefing(whatIf: Boolean = false, lang: ChatLanguage = ChatLanguage.EN) = LifeBriefing(
        language = lang,
        profile = ProfileLine("Alexia", 22, 2025, 10, "Lausanne", "single", "balanced", 3_000.0),
        start = MoneySnapshot(0, 8_000.0, 8_000.0, 350.0, 0.0, "part time"),
        end = MoneySnapshot(120, 20_000.0, 61_234.4, 900.0, 0.0, "employed"),
        lifeAtStart = listOf("Studying a bachelor", "Shared flat"),
        lifeAtEnd = listOf("Master's degree", "Rented flat", "Did a master's degree"),
        turns = listOf(
            TurnLine(1, 0, false, "Finish the master?", "Finish it", "Start working", Luck.GOOD, "You got a grant.", 8_000.0, 12_500.0, 9_000.0),
            TurnLine(2, 24, false, "Move to a studio?", "Stay", "Move", Luck.BAD, "Rent went up.", 12_500.0, 10_100.0, 4_200.0, editedInWhatIf = whatIf),
            TurnLine(3, 48, true, netWorthBeforeChf = 10_100.0, netWorthAfterChf = 15_000.0, cashAfterChf = 6_000.0),
        ),
        moments = listOf(MomentLine(30, "Cash went below zero (financing needed)")),
        goals = listOf(GoalLine("Emergency fund", 10_000.0, 36, reached = true, progressPercent = 100)),
        risk = RiskLine(-1_250.0, 30, 3, 14, 85.0),
        outlook = OutlookLine(1_000.0, 15_000.0, 30_000.0),
        drivers = listOf(DriverLine("Income (income)", 250_000.0)),
        parallelLives = listOf(ParallelLine("Other first choice", listOf("Finish the master?: Start working"), 48_000.0, 13_234.4, 1, 0)),
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
    fun briefingContainsTurnsMomentsAndEngineNumbers() {
        val text = Prompts.renderBriefing(briefing())
        listOf("Finish the master?", "Move to a studio?", "Finish it", "Start working", "Rent went up.").forEach { assertContains(text, it) }
        assertContains(text, "good luck")
        assertContains(text, "2027, age 24")                      // turn 2 at month 24
        assertContains(text, "quiet period")                      // turn 3 had no event
        assertContains(text, "net worth 61'234 CHF")              // final net worth
        assertContains(text, "-1'250 CHF")                        // lowest cash
        assertContains(text, "Months below the cushion: 14")
        assertContains(text, "Emergency fund: 10'000 CHF by 2028 -> REACHED")
        assertContains(text, "2027, age 24: Cash went below zero") // moment at month 30
        assertContains(text, "Life now: Master's degree")
        assertContains(text, "+13'234 CHF")                       // vs the other life
        assertContains(text, "Inflation 1.5 %")
    }

    @Test
    fun whatIfBranchIsFlagged() {
        assertFalse("WHAT-IF" in Prompts.renderBriefing(briefing(whatIf = false)))
        val edited = Prompts.renderBriefing(briefing(whatIf = true))
        assertContains(edited, "WHAT-IF")
        assertContains(edited, "[changed in the what-if tree]")
    }

    @Test
    fun personasAndChronicleShareRulesAndBriefing() {
        val b = briefing(lang = ChatLanguage.ES)
        val future = Prompts.chatSystem(b, Persona.FUTURE_SELF)
        val parallel = Prompts.chatSystem(b, Persona.PARALLEL_SELF)
        val chronicle = Prompts.chronicleSystem(b)
        listOf(future, parallel, chronicle).forEach {
            assertContains(it, "Never calculate new numbers")
            assertContains(it, "not financial advice")
            assertContains(it, "Answer language: Spanish")
            assertTrue(it.endsWith(Prompts.renderBriefing(b)))
        }
        assertContains(parallel, "parallel self")
        assertContains(chronicle, "at most 250 words")
    }

    @Test
    fun briefingIsDeterministic() {
        assertEquals(Prompts.renderBriefing(briefing()), Prompts.renderBriefing(briefing()))
    }

    @Test
    fun aMomentInTheMiddleOfALifeDoesNotKnowTheFuture() {
        val mid = briefing().copy(
            end = MoneySnapshot(48, 6_000.0, 15_000.0, 400.0, 0.0, "employed"),
            goals = listOf(GoalLine("House deposit", 50_000.0, 96, reached = false, progressPercent = 12)),
            parallelLives = emptyList(),
            outlook = null,
            complete = false,
        )
        val system = Prompts.chatSystem(mid, Persona.PARALLEL_SELF)          // no parallel self mid-life
        assertContains(system, "You do NOT know what happens after now")
        assertFalse(system.contains("ten years from now"))
        assertContains(system, "Now (you): 2029, age 26")
        assertContains(system, "4 of 10 years lived")
        assertContains(system, "not reached yet, the deadline has not come")
        assertContains(Prompts.chronicleSystem(mid), "chronicle of the years lived so far")
        assertEquals("Cuéntame la historia hasta ahora.", Prompts.chronicleRequest(ChatLanguage.ES, complete = false))
        // A complete life keeps the future self.
        assertContains(Prompts.chatSystem(briefing(), Persona.FUTURE_SELF), "ten years from now")
    }

    @Test
    fun briefingSaysWhereTheNumbersComeFrom() {
        val b = briefing().copy(
            drivers = listOf(DriverLine("Income (salary)", 250_000.0, "set by a decision (its case)")),
            decisionSources = listOf(SourceLine("Finish the master?", listOf("Tuition: scenario-assumptions.csv"))),
        )
        val text = Prompts.renderBriefing(b)
        assertContains(text, "## WHERE THE NUMBERS COME FROM")
        assertContains(text, "Income (salary): set by a decision (its case)")
        assertContains(text, "Tuition: scenario-assumptions.csv")
        assertContains(Prompts.chatSystem(b, Persona.FUTURE_SELF), "If asked where a number comes from")
    }
}
