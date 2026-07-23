package com.discordassistant.central.participation.adapter.outbound.embedding

import com.discordassistant.central.participation.application.port.out.ConversationEmbeddingUsageObserver
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OpenAiConversationEmbeddingAdapterTest {
    @Test
    fun `embedding 응답의 실제 입력 토큰을 운영 관측 포트에 기록한다`() {
        val response =
            """{"data":[{"index":0,"embedding":[0.1,0.2]}],"usage":{"prompt_tokens":37,"total_tokens":37}}"""
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
        val observed = AtomicReference<Pair<String, Int>>()
        val attempts = AtomicInteger()
        val observedRequestPayloadChars = AtomicInteger()
        try {
            val adapter =
                OpenAiConversationEmbeddingAdapter(
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    model = "text-embedding-3-small",
                    timeoutSeconds = 2,
                    usageObserver =
                        object : ConversationEmbeddingUsageObserver {
                            override fun recordAttempt(
                                model: String,
                                requestPayloadChars: Int,
                            ) {
                                attempts.incrementAndGet()
                                observedRequestPayloadChars.set(requestPayloadChars)
                            }

                            override fun record(
                                model: String,
                                promptTokens: Int,
                            ) {
                                observed.set(model to promptTokens)
                            }
                        },
                )

            val vectors = adapter.embed(listOf("니아 대화 예시"))

            assertThat(vectors.single().toList()).containsExactly(0.1f, 0.2f)
            assertThat(attempts.get()).isEqualTo(1)
            assertThat(observedRequestPayloadChars.get()).isPositive()
            assertThat(observed.get()).isEqualTo("text-embedding-3-small" to 37)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `embedding 성공 usage는 vector 개수 검증이 실패해도 기록한다`() {
        val response = """{"data":[],"usage":{"prompt_tokens":19,"total_tokens":19}}""".toByteArray()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/embeddings") { exchange ->
                    exchange.requestBody.readBytes()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                start()
            }
        val observed = AtomicReference<Pair<String, Int>>()
        try {
            val adapter =
                OpenAiConversationEmbeddingAdapter(
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    model = "text-embedding-3-small",
                    timeoutSeconds = 2,
                    usageObserver = ConversationEmbeddingUsageObserver { model, tokens -> observed.set(model to tokens) },
                )

            assertThatThrownBy { adapter.embed(listOf("니아 대화 예시")) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("응답 개수")
            assertThat(observed.get()).isEqualTo("text-embedding-3-small" to 19)
        } finally {
            server.stop(0)
        }
    }
}
