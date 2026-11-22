package com.lauzhack.twin.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DotEnvTest {
    @Test
    fun parsesCommonFormats() {
        val env = parseDotEnv(
            listOf(
                "# comment",
                "",
                "OPENAI_API_KEY=sk-123",
                "export TWIN_LLM_MODEL=\"gpt-4.1-mini\"",
                "ANTHROPIC_API_KEY='a=b'",
                "EMPTY=",
                "no equals sign",
            )
        )
        assertEquals("sk-123", env["OPENAI_API_KEY"])
        assertEquals("gpt-4.1-mini", env["TWIN_LLM_MODEL"])
        assertEquals("a=b", env["ANTHROPIC_API_KEY"])
        assertEquals("", env["EMPTY"])
        assertNull(env["no equals sign"])
    }
}
