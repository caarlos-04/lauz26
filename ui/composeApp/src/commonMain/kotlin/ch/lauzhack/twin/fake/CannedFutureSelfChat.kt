package ch.lauzhack.twin.fake

import ch.lauzhack.twin.ai.FutureSelfChat
import ch.lauzhack.twin.model.ChatContext
import ch.lauzhack.twin.model.ChatMessage
import ch.lauzhack.twin.model.ChatRole
import ch.lauzhack.twin.model.Lang
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/**
 * Plan B chat (docs section 10): sample answers built only from numbers in the context.
 * Picks a template by simple keyword matching. Dev C replaces this with the real LLM implementation.
 */
class CannedFutureSelfChat : FutureSelfChat {

    override suspend fun ask(context: ChatContext, history: List<ChatMessage>, question: String): ChatMessage {
        delay(600) // Simulate latency so the UI loading state is visible.
        val q = question.lowercase()
        val es = context.language == Lang.ES
        val s = context.stats
        val c = context.comparison
        val text = when {
            listOf("risk", "riesgo", "negative", "negativ", "debt", "deuda").any { it in q } ->
                if (es) "Mi caja más baja fue ${chf(s.minCash)} en el mes ${s.monthOfMinCash}, y pasé ${s.monthsNegativeCash} meses en negativo. " +
                    "Según la simulación, la probabilidad de quedarme sin liquidez era del ${pct(context.simulation?.probNegativeCash)}."
                else "My lowest cash was ${chf(s.minCash)} in month ${s.monthOfMinCash}, and I spent ${s.monthsNegativeCash} months below zero. " +
                    "According to the simulation, the chance of running out of cash was ${pct(context.simulation?.probNegativeCash)}."

            listOf("goal", "meta", "objetivo").any { it in q } ->
                if (es) "Alcancé ${s.goalsReached.size} de ${s.goalsReached.size + s.goalsMissed.size} metas. Recuerda: es una simulación, no una predicción."
                else "I reached ${s.goalsReached.size} of ${s.goalsReached.size + s.goalsMissed.size} goals. Remember: this is a simulation, not a prediction."

            c != null && listOf("other", "otro", "otra", "altern", "differ", "diferen").any { it in q } ->
                if (es) "Con las otras decisiones habría terminado con ${chf(c.alternative.finalNetWorth)} en vez de ${chf(c.chosen.finalNetWorth)} " +
                    "(diferencia: ${chf(c.deltaFinalNetWorth)}). La decisión que más pesó fue la del evento ${c.explanation.firstOrNull() ?: "-"}."
                else "With the other choices I would have ended with ${chf(c.alternative.finalNetWorth)} instead of ${chf(c.chosen.finalNetWorth)} " +
                    "(difference: ${chf(c.deltaFinalNetWorth)}). The decision that weighed most was event ${c.explanation.firstOrNull() ?: "-"}."

            else ->
                if (es) "Hola, soy tú dentro de 10 años. Mi patrimonio neto final es ${chf(s.finalNetWorth)}. " +
                    "Pregúntame por el riesgo, las metas o el camino alternativo. No es asesoramiento financiero."
                else "Hi, I'm you in 10 years. My final net worth is ${chf(s.finalNetWorth)}. " +
                    "Ask me about risk, goals or the alternative path. This is not financial advice."
        }
        return ChatMessage(ChatRole.FUTURE_SELF, text)
    }

    private fun chf(v: Double) = "${v.roundToLong()} CHF"
    private fun pct(p: Double?) = if (p == null) "?" else "${(p * 100).roundToLong()} %"
}
