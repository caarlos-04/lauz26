package com.lauzhack.twin.interview

import com.lauzhack.twin.content.AppContent
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.Objective
import com.lauzhack.twin.ui.Screen
import com.lauzhack.twin.ui.createAppState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// #15: the app builds the opening statement from the real interview (packaged resources) and from Alexia's dataset row.
class ContentTwinFactoryTest {

    private val content = runBlocking { AppContent.load() }
    private val factory = ContentTwinFactory(content.facts, content.interviewDefaults, content.assumptions.objectivePenalties, content.alexia)
    private val questions = content.interview.questions

    @Test
    fun demoIsAlexiaFromTheDataset() {
        val game = factory.demo()
        val p = game.opening.person
        assertEquals("Alexia", p.displayName)
        assertEquals(20, p.age)
        assertEquals(Employment.NOT_WORKING, p.employment)
        assertEquals("BACHELOR_IN_PROGRESS", p.facts[FactKey.EDUCATION])
        assertEquals("Lausanne", p.facts[FactKey.CITY])
        assertEquals(8000.0, game.opening.cashChf)
        assertEquals("DEMO-ALEXIA", game.opening.profileId)     // dataset row ids never leave the dataset
        assertEquals(Objective.BALANCED, game.preferences.objective)
        assertEquals(content.assumptions.objectivePenalties.getValue(Objective.BALANCED), game.preferences.penaltyChfPerMonthBelowMin)
    }

    @Test
    fun interviewAnswersBecomeTheOpeningStatement() {
        val answers = mapOf("age" to "a1", "city" to "lausanne", "household" to "single", "employment" to "not_working",
                            "education" to "bachelor_in_progress", "income" to "idk", "rent" to "rdk", "cash" to "c2",
                            "goal" to "emergency_savings", "objective" to "balanced", "mincash" to "m3")
        val game = factory.fromInterview(questions, questions.map { InterviewAnswer(it.id, answers.getValue(it.id)) })
        assertEquals(21, game.opening.person.age)
        assertEquals(1200.0, game.opening.monthlyIncomeChf)     // same case as InterviewBuilderTest / tools/test_interview.py
        assertEquals(1590.0, game.opening.monthlyRentChf)
        assertEquals(3500.0, game.preferences.minCashChf)
    }

    @Test
    fun theAppPlaysAWholeGameFromTheInterview() {
        val state = createAppState(content)
        assertEquals(questions, state.interview)
        state.startInterview()
        for (q in questions) state.answer(q.id, q.options.first().id)
        assertNotNull(state.session)
        var guard = 0
        while (state.screen != Screen.RESULTS && guard++ < 50) {
            if (state.screen == Screen.DILEMMA) state.choose(state.currentEvent!!.options.first().id) else state.nextTurn()
        }
        assertEquals(Screen.RESULTS, state.screen)
        assertTrue(state.results != null)
    }
}
