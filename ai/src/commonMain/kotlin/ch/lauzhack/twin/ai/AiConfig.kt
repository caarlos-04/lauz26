package ch.lauzhack.twin.ai

/**
 * Reads configuration (API key, model) from the environment. Keys never live in the repo or the app:
 * desktop reads environment variables; Android returns null and the UI falls back to canned answers.
 */
expect fun readEnv(name: String): String?
