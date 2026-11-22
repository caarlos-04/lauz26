package com.lauzhack.twin.ai

import com.lauzhack.twin.engine.CashBand
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.ChatMessage
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.Persona

/**
 * Chat and chronicle (P11-P12), contract of docs/README.md section 5.8.
 * Implementations only explain numbers present in [ChatContext]; they never compute or invent figures.
 */
interface FutureSelfChat {
    suspend fun ask(context: ChatContext, persona: Persona, history: List<ChatMessage>, question: String): ChatMessage

    /** The full life story at the end. */
    suspend fun chronicle(context: ChatContext): String
}

data class ChatContext(
    val result: GameResult,          // everything the LLM may use; it computes nothing
    val facts: FactCatalog,          // labels for facts and flags
    val assumptions: Assumptions,
    val language: Lang,
    /** Front addition (defaulted): turn indices the user changed in the what-if tree; empty = the played game. */
    val whatIfTurns: Set<Int> = emptySet(),
    /** Front addition (defaulted): noise band of the engine (#12), null if it provides none. */
    val noiseBand: CashBand? = null,
)

/** Implemented by chats that can fall back to offline answers, so the UI can say so. */
interface ReportsOffline {
    /** True if the last answer (ask or chronicle) did not come from the LLM. */
    val lastAnswerOffline: Boolean
}
