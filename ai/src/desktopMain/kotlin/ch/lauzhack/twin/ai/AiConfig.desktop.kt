package ch.lauzhack.twin.ai

actual fun readEnv(name: String): String? = System.getenv(name)
