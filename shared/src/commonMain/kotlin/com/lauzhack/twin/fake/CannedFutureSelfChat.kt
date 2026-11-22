package com.lauzhack.twin.fake

import com.lauzhack.twin.ai.ChatContext
import com.lauzhack.twin.ai.FutureSelfChat
import com.lauzhack.twin.ai.Prompts.chf
import com.lauzhack.twin.chronicle.Chronicle
import com.lauzhack.twin.chronicle.ChronicleTemplates
import com.lauzhack.twin.model.ChatMessage
import com.lauzhack.twin.model.ChatRole
import com.lauzhack.twin.model.EventCatalog
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.ParallelKind
import com.lauzhack.twin.model.Persona
import kotlinx.coroutines.delay

/**
 * Plan B chat (docs section 10): sample answers built only from numbers in the context, picked by keyword.
 * Used when no LLM key is configured, and as the offline fallback of the real agent.
 */
class CannedFutureSelfChat(
    private val latencyMs: Long = 600,
    /** With the content templates and events, the offline chronicle tells the life line beat by beat (#18). */
    private val templates: ChronicleTemplates? = null,
    private val events: EventCatalog? = null,
) : FutureSelfChat {

    override suspend fun ask(context: ChatContext, persona: Persona, history: List<ChatMessage>, question: String): ChatMessage {
        delay(latencyMs) // Simulate latency so the UI loading state is visible.
        val q = question.lowercase()
        val es = context.language == Lang.ES
        val r = context.result
        val s = r.stats
        val other = r.parallelLives.firstOrNull { it.kind == ParallelKind.OTHER_FIRST_CHOICE }
        val text = when {
            persona == Persona.PARALLEL_SELF && other != null ->
                if (es) "Soy tu yo paralelo: en la primera decisión elegí lo contrario. Terminé con ${chf(other.stats.finalNetWorth)} " +
                    "de patrimonio y pasé ${other.stats.monthsNegativeCash} meses en negativo. Es una simulación, no una predicción."
                else "I'm your parallel self: at the first decision I chose the other option. I ended with ${chf(other.stats.finalNetWorth)} " +
                    "of net worth and spent ${other.stats.monthsNegativeCash} months below zero. It is a simulation, not a prediction."

            listOf("risk", "riesgo", "negative", "negativ", "debt", "deuda").any { it in q } ->
                if (es) "Mi caja más baja fue ${chf(s.minCash)} en el mes ${s.monthOfMinCash}; pasé ${s.monthsNegativeCash} meses en negativo " +
                    "y ${s.monthsBelowMinCash} por debajo de mi colchón. Financiar la caja negativa me costó ${chf(s.financingCostChf)}."
                else "My lowest cash was ${chf(s.minCash)} in month ${s.monthOfMinCash}; I spent ${s.monthsNegativeCash} months below zero " +
                    "and ${s.monthsBelowMinCash} below my cushion. Financing negative cash cost me ${chf(s.financingCostChf)}."

            listOf("goal", "meta", "objetivo").any { it in q } ->
                if (es) "Alcancé ${s.goalsReached.size} de ${s.goalsReached.size + s.goalsMissed.size} metas. Recuerda: es una simulación, no una predicción."
                else "I reached ${s.goalsReached.size} of ${s.goalsReached.size + s.goalsMissed.size} goals. Remember: this is a simulation, not a prediction."

            other != null && listOf("other", "otro", "otra", "altern", "differ", "diferen").any { it in q } ->
                if (es) "Si hubiera elegido lo contrario en la primera decisión, habría terminado con ${chf(other.stats.finalNetWorth)} " +
                    "en vez de ${chf(s.finalNetWorth)} (diferencia: ${chf(s.finalNetWorth - other.stats.finalNetWorth, true)})."
                else "Had I chosen the other option at the first decision, I would have ended with ${chf(other.stats.finalNetWorth)} " +
                    "instead of ${chf(s.finalNetWorth)} (difference: ${chf(s.finalNetWorth - other.stats.finalNetWorth, true)})."

            else ->
                if (es) "Hola, soy tú dentro de 10 años. Mi patrimonio neto final es ${chf(s.finalNetWorth)}. " +
                    "Pregúntame por el riesgo, las metas o la otra vida. No es asesoramiento financiero."
                else "Hi, I'm you in 10 years. My final net worth is ${chf(s.finalNetWorth)}. " +
                    "Ask me about risk, goals or the other life. This is not financial advice."
        }
        return ChatMessage(ChatRole.TWIN, text)
    }

    /**
     * Plan B chronicle (no network): the life line told with content/chronicle.json (one sentence per beat, only the
     * numbers the beats carry) when the templates are given, otherwise a short summary over the stats.
     */
    override suspend fun chronicle(context: ChatContext): String {
        delay(latencyMs)
        val es = context.language == Lang.ES
        val r = context.result
        if (templates != null && events != null) {
            val startYear = r.session.initial.period.take(4).toIntOrNull() ?: Chronicle.START_YEAR
            val lines = Chronicle.render(r.beats, context.language, templates, events, context.facts, startYear)
            val story = Chronicle.paragraphs(lines).joinToString("\n\n")
            return story + "\n\n" + if (es) "Es una simulación con datos sintéticos, no una predicción." else "It is a simulation with synthetic data, not a prediction."
        }
        val s = r.stats
        val decisions = r.session.turns.count { it.eventId != null }
        val quiet = r.session.turns.size - decisions
        return if (es)
            "En diez años tomé $decisions decisiones importantes y viví $quiet periodos tranquilos. " +
                "Mi momento más difícil fue el mes ${s.monthOfMinCash}, con ${chf(s.minCash)} en caja. " +
                "Terminé con un patrimonio de ${chf(s.finalNetWorth)} y alcancé ${s.goalsReached.size} de ${s.goalsReached.size + s.goalsMissed.size} metas. " +
                "Es una simulación con datos sintéticos, no una predicción."
        else
            "Over ten years I took $decisions big decisions and lived $quiet quiet periods. " +
                "My hardest moment was month ${s.monthOfMinCash}, with ${chf(s.minCash)} in cash. " +
                "I ended with a net worth of ${chf(s.finalNetWorth)} and reached ${s.goalsReached.size} of ${s.goalsReached.size + s.goalsMissed.size} goals. " +
                "It is a simulation with synthetic data, not a prediction."
    }
}
