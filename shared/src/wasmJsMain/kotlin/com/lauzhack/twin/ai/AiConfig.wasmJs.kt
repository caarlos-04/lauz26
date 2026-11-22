package com.lauzhack.twin.ai

// No API keys in the browser (anyone could read them): the web app uses the canned chat.
actual fun readEnv(name: String): String? = null
