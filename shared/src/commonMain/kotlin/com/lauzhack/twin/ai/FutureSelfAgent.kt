package com.lauzhack.twin.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams

/** One previous message of the conversation. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/** Produces an answer for a full system prompt (static part + briefing). Lets the chat be tested with a fake. */
fun interface ReplyGenerator {
    suspend fun reply(systemPrompt: String, history: List<ChatTurn>, question: String): String
}

/** LLM providers the agent can use. Chosen by which API key is present in the environment. */
enum class AiProvider(val keyEnv: String, val defaultModel: String) {
    OPENAI("OPENAI_API_KEY", "gpt-4.1-mini"),
    ANTHROPIC("ANTHROPIC_API_KEY", "claude-haiku-5-5"),
}

/**
 * Koog agent that speaks as the user's self in ten years.
 *
 * A fresh single-run agent is built per call: its prompt is the system prompt built by [Prompts] (static part +
 * briefing of the branch selected in the decision tree), followed by the chat history.
 * So changing the tree changes the agent's knowledge on the very next question.
 */
class FutureSelfAgent(
    val provider: AiProvider,
    apiKey: String,
    val modelId: String = provider.defaultModel,
    private val maxTokens: Int = 2_048,
) : ReplyGenerator {

    // Declared here (not taken from Koog's catalog) so any model id can be set with TWIN_LLM_MODEL.
    // Koog agents require the Tools capability even with an empty tool registry.
    private val model: LLModel = when (provider) {
        AiProvider.OPENAI -> LLModel(
            provider = LLMProvider.OpenAI,
            id = modelId,
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.OpenAIEndpoint.Completions),
            contextLength = 128_000,
            maxOutputTokens = 32_768,
        )
        AiProvider.ANTHROPIC -> LLModel(
            provider = LLMProvider.Anthropic,
            id = modelId,
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.Thinking),
            contextLength = 200_000,
            maxOutputTokens = 64_000,
        )
    }

    private val client: LLMClient = when (provider) {
        AiProvider.OPENAI -> OpenAILLMClient(apiKey = apiKey, httpClientFactory = KtorKoogHttpClient.Factory())
        AiProvider.ANTHROPIC -> AnthropicLLMClient(
            apiKey = apiKey,
            settings = AnthropicClientSettings(modelVersionsMap = mapOf(model to modelId)),
            httpClientFactory = KtorKoogHttpClient.Factory(),
        )
    }

    private val executor = MultiLLMPromptExecutor(mapOf(model.provider to client))

    override suspend fun reply(systemPrompt: String, history: List<ChatTurn>, question: String): String {
        // No temperature: newer models reject non-default sampling parameters.
        val chatPrompt = prompt("future-self", LLMParams(maxTokens = maxTokens)) {
            system(systemPrompt)
            history.forEach { if (it.fromUser) user(it.text) else assistant(it.text) }
        }
        val agent = AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(prompt = chatPrompt, model = model, maxAgentIterations = 5),
        )
        return agent.run(question).trim()
    }

    companion object {
        const val MODEL_ENV = "TWIN_LLM_MODEL"

        /** First provider with an API key in the environment (OpenAI first), or null. */
        fun configuredProvider(): AiProvider? =
            AiProvider.entries.firstOrNull { !readEnv(it.keyEnv).isNullOrBlank() }

        /** Builds the agent from environment variables, or null when no API key is configured. */
        fun fromEnvironment(): FutureSelfAgent? {
            val provider = configuredProvider() ?: return null
            val model = readEnv(MODEL_ENV)?.takeIf { it.isNotBlank() } ?: provider.defaultModel
            return FutureSelfAgent(provider, readEnv(provider.keyEnv)!!, model)
        }
    }
}
