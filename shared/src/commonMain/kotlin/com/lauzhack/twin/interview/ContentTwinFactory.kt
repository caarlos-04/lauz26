package com.lauzhack.twin.interview

import com.lauzhack.twin.engine.NewGame
import com.lauzhack.twin.engine.TwinFactory
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.InterviewQuestion
import com.lauzhack.twin.model.Objective

/**
 * P1-P2 for real (#15): the interview answers become the opening statement with the port of `statement.new_person`
 * ([InterviewBuilder]); the demo is the challenge's Alexia as she is in the dataset (content/alexia.json).
 */
class ContentTwinFactory(
    private val facts: FactCatalog,
    private val defaults: InterviewDefaults,
    private val objectivePenalties: Map<Objective, Double>,
    private val alexia: DemoProfile,
) : TwinFactory {

    override fun fromInterview(questions: List<InterviewQuestion>, answers: List<InterviewAnswer>): NewGame {
        val outcome = InterviewBuilder.build(InterviewCatalog(version = 1, questions = questions), answers, facts, defaults, objectivePenalties)
        return NewGame(outcome.statement, outcome.preferences)
    }

    override fun demo(): NewGame = NewGame(alexia.statement, alexia.preferences)
}
