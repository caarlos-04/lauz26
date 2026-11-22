package com.lauzhack.twin.ai

import java.io.File

/**
 * Environment variable first; otherwise the value from the nearest `.env` file, searching from the working
 * directory upwards (Gradle runs desktopApp from desktopApp/, so the repo-root `.env` is found).
 * `.env` is git-ignored: each teammate keeps their own key there (template: `.env.example`).
 */
actual fun readEnv(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: dotEnv[name]?.takeIf { it.isNotBlank() }

private val dotEnv: Map<String, String> by lazy {
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, ".env") }
        .firstOrNull { it.isFile }
        ?.let { parseDotEnv(it.readLines()) }
        .orEmpty()
}

/** Supports `KEY=value`, `export KEY=value`, single/double quotes and `#` comment lines. */
internal fun parseDotEnv(lines: List<String>): Map<String, String> = lines
    .map { it.trim().removePrefix("export ").trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
    .associate { line ->
        val key = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
        key to value
    }
