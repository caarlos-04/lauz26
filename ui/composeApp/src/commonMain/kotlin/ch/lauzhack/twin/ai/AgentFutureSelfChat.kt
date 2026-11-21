package ch.lauzhack.twin.ai

import ch.lauzhack.twin.model.ChatContext
import ch.lauzhack.twin.model.ChatMessage
import ch.lauzhack.twin.model.ChatRole
import kotlinx.coroutines.CancellationException

/**
 * [FutureSelfChat] backed by the Koog agent in `ai/`. Every question rebuilds the briefing from the
 * branch currently selected in the decision tree. If the LLM call fails (no network, bad key, rate
 * limit), the answer comes from [fallback] and is marked offline so the UI can say so.
 */
class AgentFutureSelfChat(
    private val generator: ReplyGenerator,
    private val mapper: BriefingMapper,
    private val fallback: FutureSelfChat,
) : FutureSelfChat {

    override suspend fun ask(context: ChatContext, history: List<ChatMessage>, question: String): ChatMessage =
        try {
            val briefing = mapper.map(context)
            // Offline (canned) answers are not the agent's words: keep them out of its history.
            val turns = history.filterNot { it.offline }.map { ChatTurn(fromUser = it.role == ChatRole.USER, text = it.text) }
            ChatMessage(ChatRole.FUTURE_SELF, generator.reply(briefing, turns, question))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Future-self agent failed, using offline answer: ${e.message}")
            fallback.ask(context, history, question).copy(offline = true)
        }
}
