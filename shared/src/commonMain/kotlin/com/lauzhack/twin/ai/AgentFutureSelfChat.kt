package com.lauzhack.twin.ai

import com.lauzhack.twin.model.ChatMessage
import com.lauzhack.twin.model.ChatRole
import com.lauzhack.twin.model.Persona
import kotlinx.coroutines.CancellationException

/**
 * [FutureSelfChat] backed by the Koog agent ([FutureSelfAgent]). Every call rebuilds the briefing from the
 * branch currently selected in the decision tree. If the LLM call fails (no network, bad key, rate limit),
 * the answer comes from [fallback] and [lastAnswerOffline] is set so the UI can say so.
 *
 * @param offlineHistory tells which history messages were offline answers; they are not the agent's words,
 *   so they are left out of the conversation sent to the LLM.
 */
class AgentFutureSelfChat(
    private val generator: ReplyGenerator,
    private val mapper: BriefingMapper,
    private val fallback: FutureSelfChat,
    private val offlineHistory: (ChatMessage) -> Boolean = { false },
) : FutureSelfChat, ReportsOffline {

    override var lastAnswerOffline: Boolean = false
        private set

    override suspend fun ask(context: ChatContext, persona: Persona, history: List<ChatMessage>, question: String): ChatMessage =
        attempt({ fallback.ask(context, persona, history, question) }) {
            val briefing = mapper.map(context)
            val turns = history.filterNot(offlineHistory).map { ChatTurn(fromUser = it.role == ChatRole.USER, text = it.text) }
            ChatMessage(ChatRole.TWIN, generator.reply(Prompts.chatSystem(briefing, persona), turns, question))
        }

    override suspend fun chronicle(context: ChatContext): String =
        attempt({ fallback.chronicle(context) }) {
            val briefing = mapper.map(context)
            generator.reply(Prompts.chronicleSystem(briefing), emptyList(), Prompts.chronicleRequest(briefing.language, briefing.complete))
        }

    private suspend fun <T> attempt(offline: suspend () -> T, online: suspend () -> T): T =
        try {
            online().also { lastAnswerOffline = false }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Future-self agent failed, using offline answer: ${e.message}")
            lastAnswerOffline = true
            offline()
        }
}
