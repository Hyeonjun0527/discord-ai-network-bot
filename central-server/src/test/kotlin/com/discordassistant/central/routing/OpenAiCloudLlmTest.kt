package com.discordassistant.central.routing

import com.discordassistant.central.routing.application.CloudLlmCachePolicy
import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.CloudLlmPurpose
import com.discordassistant.central.routing.application.CloudLlmRequestOptions
import com.discordassistant.central.routing.application.CloudLlmUsage
import com.discordassistant.central.routing.application.CloudLlmUsageObserver
import com.discordassistant.central.routing.application.OpenAiCloudLlm
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
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
            assertEquals("explicit", payload.path("prompt_cache_options").path("mode").asText())
            assertFalse(payload.has("prompt_cache_key"))
            assertFalse(
                payload
                    .path("input")
                    .single()
                    .path("content")
                    .isArray,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `explicit cache는 고정 prefix만 저장하고 동적 suffix와 출력 상한을 그대로 보낸다`() {
        val capturedBody = AtomicReference<String>()
        val observed = AtomicReference<Pair<CloudLlmPurpose, CloudLlmUsage>>()
        val response =
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"판단"}]}],"usage":{"input_tokens":120,"output_tokens":7,"input_tokens_details":{"cached_tokens":80,"cache_write_tokens":32}}}"""
                .toByteArray()
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
            val prompt = "고정 판단 규칙\n동적 장면"
            val prefixChars = "고정 판단 규칙\n".length
            val cache = CloudLlmCachePolicy.stablePrefix("nia-judge:v14", prompt, prefixChars)
            val observer = CloudLlmUsageObserver { _, purpose, usage -> observed.set(purpose to usage) }

            val result =
                client(server, usageObserver = observer, promptCacheWritesEnabled = true).generate(
                    prompt,
                    "gpt-5.6-luna",
                    history = emptyList(),
                    thinking = null,
                    options =
                        CloudLlmRequestOptions(
                            purpose = CloudLlmPurpose.NIA_JUDGE,
                            maxOutputTokens = 512,
                            cachePolicy = cache,
                        ),
                )

            val payload = mapper.readTree(capturedBody.get())
            assertEquals("explicit", payload.path("prompt_cache_options").path("mode").asText())
            assertEquals(cache.key, payload.path("prompt_cache_key").asText())
            assertEquals(512, payload.path("max_output_tokens").asInt())
            val blocks = payload.path("input").single().path("content")
            assertEquals(prompt.take(prefixChars), blocks[0].path("text").asText())
            assertEquals("explicit", blocks[0].path("prompt_cache_breakpoint").path("mode").asText())
            assertEquals(prompt.drop(prefixChars), blocks[1].path("text").asText())
            assertEquals(prompt, blocks.joinToString("") { it.path("text").asText() })
            assertEquals(80, result.usage.cachedPromptTokens)
            assertEquals(32, result.usage.cacheWritePromptTokens)
            assertEquals(CloudLlmPurpose.NIA_JUDGE, observed.get().first)
            assertEquals(result.usage, observed.get().second)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cache write 전역 gate가 꺼져 있으면 caller의 breakpoint 요청도 no-write로 내린다`() {
        val capturedBody = AtomicReference<String>()
        val response = responseBody("판단").toByteArray()
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
            val prompt = "고정 판단 규칙\n동적 장면"
            client(server).generate(
                prompt = prompt,
                model = "gpt-5.6-luna",
                history = emptyList(),
                thinking = null,
                options =
                    CloudLlmRequestOptions(
                        purpose = CloudLlmPurpose.NIA_JUDGE,
                        cachePolicy =
                            CloudLlmCachePolicy.stablePrefix(
                                "nia-judge:test",
                                prompt,
                                "고정 판단 규칙\n".length,
                            ),
                    ),
            )

            val payload = mapper.readTree(capturedBody.get())
            assertEquals("explicit", payload.path("prompt_cache_options").path("mode").asText())
            assertFalse(payload.has("prompt_cache_key"))
            assertFalse(
                payload
                    .path("input")
                    .single()
                    .path("content")
                    .isArray,
            )
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
    fun `request retry override는 외부 retry 안에서 숨은 재호출을 막는다`() {
        val attempts = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    attempts.incrementAndGet()
                    exchange.requestBody.readBytes()
                    try {
                        Thread.sleep(500)
                        val body = responseBody("늦은 답").toByteArray()
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
            assertThrows(CloudLlmException::class.java) {
                client(server, timeoutSeconds = 2, maxRetries = 2).generate(
                    prompt = "니아야",
                    model = "gpt-5.6-luna",
                    history = emptyList(),
                    thinking = null,
                    options =
                        CloudLlmRequestOptions(
                            requestTimeout = Duration.ofMillis(100),
                            maxRetries = 0,
                        ),
                )
            }
            assertEquals(1, attempts.get())
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `HTTP 실패도 실제 provider 요청 횟수에는 남고 usage token으로 거짓 기록하지 않는다`() {
        val requestAttempts = AtomicInteger()
        val observedRequestPayloadChars = AtomicInteger()
        val observedCacheWriteRequested = AtomicReference<Boolean>()
        val usageRecords = AtomicInteger()
        val response = """{"error":{"code":"upstream_error","message":"failed"}}""".toByteArray()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    exchange.requestBody.readBytes()
                    exchange.sendResponseHeaders(500, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                start()
            }
        val observer =
            object : CloudLlmUsageObserver {
                override fun recordAttempt(
                    model: String,
                    purpose: CloudLlmPurpose,
                    requestPayloadChars: Int,
                    cacheWriteRequested: Boolean,
                ) {
                    requestAttempts.incrementAndGet()
                    observedRequestPayloadChars.set(requestPayloadChars)
                    observedCacheWriteRequested.set(cacheWriteRequested)
                }

                override fun record(
                    model: String,
                    purpose: CloudLlmPurpose,
                    usage: CloudLlmUsage,
                ) {
                    usageRecords.incrementAndGet()
                }
            }
        try {
            assertThrows(CloudLlmException::class.java) {
                client(server, usageObserver = observer).generate(
                    prompt = "니아야",
                    model = "gpt-5.6-luna",
                    history = emptyList(),
                    thinking = null,
                    options = CloudLlmRequestOptions(purpose = CloudLlmPurpose.NIA_JUDGE, maxRetries = 0),
                )
            }

            assertEquals(1, requestAttempts.get())
            assertTrue(observedRequestPayloadChars.get() > 0)
            assertEquals(false, observedCacheWriteRequested.get())
            assertEquals(0, usageRecords.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `HTTP 성공 usage는 빈 출력 때문에 의미 파싱이 실패해도 기록한다`() {
        val observed = AtomicReference<Pair<CloudLlmPurpose, CloudLlmUsage>>()
        val response =
            """{"output":[],"usage":{"input_tokens":91,"output_tokens":6,"input_tokens_details":{"cached_tokens":40,"cache_write_tokens":0}}}"""
                .toByteArray()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    exchange.requestBody.readBytes()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                start()
            }
        try {
            val observer = CloudLlmUsageObserver { _, purpose, usage -> observed.set(purpose to usage) }

            assertThrows(CloudLlmException::class.java) {
                client(server, usageObserver = observer).generate(
                    prompt = "니아야",
                    model = "gpt-5.6-luna",
                    history = emptyList(),
                    thinking = null,
                    options = CloudLlmRequestOptions(purpose = CloudLlmPurpose.NIA_JUDGE, maxRetries = 0),
                )
            }

            assertEquals(CloudLlmPurpose.NIA_JUDGE, observed.get().first)
            assertEquals(
                CloudLlmUsage(
                    promptTokens = 91,
                    completionTokens = 6,
                    cachedPromptTokens = 40,
                    cacheWritePromptTokens = 0,
                ),
                observed.get().second,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chat function schema is converted to Responses tool schema`() {
        val capturedBody = AtomicReference<String>()
        val observed = AtomicReference<Pair<CloudLlmPurpose, CloudLlmUsage>>()
        val response =
            """
            {
              "output": [
                {"type":"function_call","call_id":"call_1","name":"ban_member","arguments":"{}"}
              ],
              "usage": {"input_tokens":20,"output_tokens":8}
            }
            """.trimIndent().toByteArray()
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

            val result =
                client(
                    server,
                    usageObserver = CloudLlmUsageObserver { _, purpose, usage -> observed.set(purpose to usage) },
                ).generateWithTools(
                    "관리 지침",
                    "차단해",
                    tools,
                    "gpt-5.6-luna",
                    CloudLlmRequestOptions(
                        purpose = CloudLlmPurpose.ADMIN_ACTION_ROUTER,
                        maxOutputTokens = 1_024,
                    ),
                )

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
            assertEquals(1_024, payload.path("max_output_tokens").asInt())
            assertEquals(CloudLlmPurpose.ADMIN_ACTION_ROUTER, observed.get().first)
            assertEquals(result.usage, observed.get().second)
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
        promptCacheWritesEnabled: Boolean = false,
        usageObserver: CloudLlmUsageObserver = CloudLlmUsageObserver.NOOP,
    ) = OpenAiCloudLlm(
        apiKey = "test-key",
        baseUrl = "http://127.0.0.1:${server.address.port}",
        timeoutSeconds = timeoutSeconds,
        maxRetries = maxRetries,
        promptCacheWritesEnabled = promptCacheWritesEnabled,
        usageObserver = usageObserver,
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
