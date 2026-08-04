package com.discordassistant.central.platform.openai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class OpenAiSpeechStyleEmbeddingAdapterTest {
    @Test
    fun `embedding API 요청은 배열 자체가 아니라 model과 input을 가진 JSON object다`() {
        val adapter =
            OpenAiSpeechStyleEmbeddingAdapter(
                apiKey = "test-key",
                baseUrl = "https://example.test/v1",
                model = "text-embedding-3-small",
                timeoutSeconds = 1,
            )

        val payload = jacksonObjectMapper().readTree(adapter.requestPayloadJson(listOf("first", "second")))

        assertThat(payload.isObject).isTrue()
        assertThat(payload.path("model").asText()).isEqualTo("text-embedding-3-small")
        assertThat(payload.path("encoding_format").asText()).isEqualTo("float")
        assertThat(payload.path("input").map { it.asText() }).containsExactly("first", "second")
    }

    @Test
    fun `성공한 embedding 호출은 원문 없이 요청 수와 입력 토큰만 관측한다`() {
        val response =
            """{"data":[{"embedding":[1.0,0.0],"index":0}],"usage":{"prompt_tokens":3,"total_tokens":3}}"""
                .toByteArray()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/embeddings") { exchange ->
                    exchange.requestBody.readBytes()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                start()
            }
        val metrics = CapturingMetrics()
        try {
            val adapter =
                OpenAiSpeechStyleEmbeddingAdapter(
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    model = "text-embedding-3-small",
                    timeoutSeconds = 1,
                    metrics = metrics,
                )

            val vectors = adapter.embedAll(listOf("짧은 현재 장면"))

            assertThat(vectors).containsExactly(floatArrayOf(1f, 0f))
            assertThat(metrics.attempts).isEqualTo(1)
            assertThat(metrics.inputTokens).isEqualTo(3)
        } finally {
            server.stop(0)
        }
    }

    private class CapturingMetrics : SpeechStyleEmbeddingMetrics {
        var attempts: Int = 0
        var inputTokens: Int = 0

        override fun recordAttempt(
            model: String,
            inputChars: Int,
        ) {
            attempts++
        }

        override fun recordInputTokens(
            model: String,
            inputTokens: Int,
        ) {
            this.inputTokens += inputTokens
        }
    }
}
