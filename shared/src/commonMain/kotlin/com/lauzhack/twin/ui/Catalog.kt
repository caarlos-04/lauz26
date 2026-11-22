package com.lauzhack.twin.ui

import com.lauzhack.twin.engine.outcomeText
import com.lauzhack.twin.model.Action
import com.lauzhack.twin.model.ActionCatalog
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.LocalizedText
import com.lauzhack.twin.model.Shock

/** The actions and shocks of content/events.json by id, for the screens: titles, outcome stories and sources. */
class Catalog(val content: ActionCatalog) {
    private val actions = content.actions.associateBy { it.id }
    private val shocks = content.shocks.associateBy { it.id }

    fun action(id: String?): Action? = id?.let { actions[it] }
    fun shock(id: String?): Shock? = id?.let { shocks[it] }

    /** Title of an action or a shock (a story beat's eventId is one or the other). */
    fun title(id: String?): LocalizedText? = action(id)?.title ?: shock(id)?.title

    /** The outcome sentence of an action or shock with the values drawn for it. */
    fun outcome(id: String?, values: Map<String, Double>, lang: Lang): String? =
        action(id)?.let { outcomeText(it.outcome, it.uncertain, values, lang) }
            ?: shock(id)?.let { outcomeText(it.outcome, it.uncertain, values, lang) }

    fun sources(id: String?): List<String> = action(id)?.sources ?: shock(id)?.sources ?: emptyList()
}
