package ch.lauzhack.twin.ai

import ch.lauzhack.twin.model.ChatContext
import ch.lauzhack.twin.model.ChatMessage

/**
 * Chat with the "future self" (P10, Dev C). Implementations must only explain numbers
 * present in [ChatContext]; they never compute or invent figures.
 */
interface FutureSelfChat {
    suspend fun ask(context: ChatContext, history: List<ChatMessage>, question: String): ChatMessage
}
