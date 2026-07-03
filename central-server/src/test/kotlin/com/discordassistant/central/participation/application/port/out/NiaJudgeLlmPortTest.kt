package com.discordassistant.central.participation.application.port.out

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class NiaJudgeLlmPortTest {
    @Test
    fun `request string does not expose raw prompt text`() {
        val request =
            NiaJudgeLlmRequest(
                prompt = "야 이럴땐 위로하라고",
                promptVersion = "nia-judge-prompt-v1",
                seed = 42L,
                timeoutMillis = 5_000,
                metadata = mapOf("scope" to "test"),
            )

        assertThat(request.toString()).doesNotContain("위로하라고")
        assertThat(request.toString()).contains("promptChars=", "promptHash=")
    }

    @Test
    fun `response string does not expose model output content`() {
        val response =
            NiaJudgeLlmResponse(
                content = """{"schema":"nia.participation-judge-output.v1","action":"SPEAK"}""",
                modelVersion = "local-judge-v1",
                finishReason = "stop",
                promptTokens = 10,
                completionTokens = 8,
                latencyMillis = 120,
            )

        assertThat(response.toString()).doesNotContain("SPEAK")
        assertThat(response.toString()).contains("contentChars=", "contentHash=")
    }

    @Test
    fun `request validates stable provider-neutral fields`() {
        assertThatThrownBy {
            NiaJudgeLlmRequest(
                prompt = " ",
                promptVersion = "nia-judge-prompt-v1",
                seed = 1L,
                timeoutMillis = 1_000,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            NiaJudgeLlmRequest(
                prompt = "raw",
                promptVersion = "bad prompt version",
                seed = 1L,
                timeoutMillis = 1_000,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
