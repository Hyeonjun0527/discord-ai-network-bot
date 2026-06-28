package com.discordassistant.central.routing

import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.ZaiCloudLlm
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ZaiCloudLlmTest {
    @Test
    fun `configured timeout 초과 LLM 응답은 실패로 본다`() {
        val body = """{"choices":[{"message":{"content":"늦은 답"}}]}""".toByteArray()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    exchange.requestBody.readBytes()
                    try {
                        Thread.sleep(1_500)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    } catch (_: Exception) {
                        exchange.close()
                    }
                }
                start()
            }
        try {
            val client =
                ZaiCloudLlm(
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    timeoutSeconds = 1,
                    maxRetries = 0,
                )

            assertThrows(CloudLlmException::class.java) {
                client.generate("니아야", "glm-4.5-air")
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `timeout 이면 같은 LLM 요청을 최대 두 번 재시도하고 늦은 응답은 버린다`() {
        val attempts = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val attempt = attempts.incrementAndGet()
                    exchange.requestBody.readBytes()
                    try {
                        val content =
                            if (attempt < 3) {
                                Thread.sleep(1_500)
                                "늦은 답 $attempt"
                            } else {
                                "세 번째 빠른 답"
                            }
                        val body = """{"choices":[{"message":{"content":"$content"}}]}""".toByteArray()
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
            val client =
                ZaiCloudLlm(
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    timeoutSeconds = 1,
                    maxRetries = 2,
                )

            val result = client.generate("니아야", "glm-4.5-air")

            assertEquals("세 번째 빠른 답", result.text)
            assertEquals(3, attempts.get())
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
