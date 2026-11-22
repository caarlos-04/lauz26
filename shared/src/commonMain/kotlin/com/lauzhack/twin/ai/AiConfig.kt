package com.lauzhack.twin.ai

/**
 * Reads configuration (API key, model) from the environment. Keys never live in the repo or the app:
 * desktop reads environment variables and .env; Android debug builds get them at build time (AndroidEnv); web has none
 * and the UI falls back to canned answers.
 */
expect fun readEnv(name: String): String?
