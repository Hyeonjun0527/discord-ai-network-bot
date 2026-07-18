package com.discordassistant.central.routing

import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.OpenAiCloudLlm
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OpenAiCloudLlmTest {
    private val mapper = ObjectMapper()

    @Test
    fun `모든 요청은 Responses API와 Luna reasoning none을 사용한다`() {
        val capturedPath = AtomicReference<String>()
        val capturedBody = AtomicReference<String>()
        val response = responseBody("응 여기 있어").toByteArray()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    capturedPath.set(exchange.requestURI.path)
                    capturedBody.set(String(exchange.requestBody.readBytes()))
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                start()
            }
        try {
            val client = client(server)

            val result = client.generateSampled("니아야", "gpt-5.6-luna", temperature = 0.9)

            assertEquals("응 여기 있어", result.text)
            assertEquals("/responses", capturedPath.get())
            val payload = mapper.readTree(capturedBody.get())
            assertEquals("gpt-5.6-luna", payload.path("model").asText())
            assertEquals("none", payload.path("reasoning").path("effort").asText())
            assertFalse(payload.has("temperature"))
            assertEquals(false, payload.path("store").asBoolean())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `configured timeout 초과 응답은 실패로 본다`() {
        val server = delayedServer(delaysBeforeResponse = 1, delayMillis = 1_500)
        try {
            assertThrows(CloudLlmException::class.java) {
                client(server, timeoutSeconds = 1, maxRetries = 0).generate("니아야", "gpt-5.6-luna")
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chat function schema is converted to Responses tool schema`() {
        val capturedBody = AtomicReference<String>()
        val response =
            """{"output":[{"type":"function_call","call_id":"call_1","name":"ban_member","arguments":"{}"}]}""".toByteArray()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    capturedBody.set(String(exchange.requestBody.readBytes()))
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                start()
            }
        try {
            val tools =
                """[{"type":"function","function":{"name":"ban_member","description":"ban","parameters":{"type":"object"}}}]"""

            val result = client(server).generateWithTools("관리 지침", "차단해", tools, "gpt-5.6-luna")

            assertEquals("ban_member", result.toolCalls.single().name)
            val payload = mapper.readTree(capturedBody.get())
            assertEquals("관리 지침", payload.path("instructions").asText())
            assertEquals(
                "ban_member",
                payload
                    .path("tools")
                    .first()
                    .path("name")
                    .asText(),
            )
            assertFalse(payload.path("tools").first().has("function"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `timeout 이면 설정 횟수만큼 재시도한다`() {
        val attempts = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val attempt = attempts.incrementAndGet()
                    exchange.requestBody.readBytes()
                    try {
                        if (attempt < 3) Thread.sleep(1_500)
                        val body = responseBody(if (attempt < 3) "늦은 답" else "세 번째 빠른 답").toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    } catch (_: Exception) {
                        exchange.close()
                    }
                }
                setExecutor(executor)
                start()
            }
        try {
            val result = client(server, timeoutSeconds = 1, maxRetries = 2).generate("니아야", "gpt-5.6-luna")
            assertEquals("세 번째 빠른 답", result.text)
            assertEquals(3, attempts.get())
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun client(
        server: HttpServer,
        timeoutSeconds: Long = 2,
        maxRetries: Int = 0,
    ) = OpenAiCloudLlm(
        apiKey = "test-key",
        baseUrl = "http://127.0.0.1:${server.address.port}",
        timeoutSeconds = timeoutSeconds,
        maxRetries = maxRetries,
    )

    private fun delayedServer(
        delaysBeforeResponse: Int,
        delayMillis: Long,
    ): HttpServer {
        val attempts = AtomicInteger()
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.readBytes()
                try {
                    if (attempts.incrementAndGet() <= delaysBeforeResponse) Thread.sleep(delayMillis)
                    val body = responseBody("답").toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } catch (_: Exception) {
                    exchange.close()
                }
            }
            start()
        }
    }

    private fun responseBody(text: String): String =
        """{"output":[{"type":"message","content":[{"type":"output_text","text":"$text"}]}],"usage":{"input_tokens":3,"output_tokens":4}}"""
}
