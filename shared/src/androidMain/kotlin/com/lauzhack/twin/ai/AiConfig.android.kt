package com.lauzhack.twin.ai

/**
 * LLM settings on Android: there are no environment variables, so the app sets them at start-up (MainActivity).
 * Debug builds get them from the developer's .env at build time; release builds get none, and the chat uses the
 * canned answers (docs/ai.md, "Android").
 */
object AndroidEnv {
    var values: Map<String, String> = emptyMap()
}

actual fun readEnv(name: String): String? = AndroidEnv.values[name]?.takeIf { it.isNotBlank() }
