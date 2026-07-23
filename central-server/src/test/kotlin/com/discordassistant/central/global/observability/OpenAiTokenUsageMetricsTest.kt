package com.discordassistant.central.global.observability

import com.discordassistant.central.participation.adapter.outbound.embedding.OpenAiConversationEmbeddingAdapter
import com.discordassistant.central.routing.application.CloudLlmPurpose
import com.discordassistant.central.routing.application.CloudLlmRequestOptions
import com.discordassistant.central.routing.application.CloudLlmUsage
import com.discordassistant.central.routing.application.OpenAiCloudLlm
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.util.ReflectionTestUtils
import java.net.InetSocketAddress

class OpenAiTokenUsageMetricsTest {
    @Test
    fun `Spring은 OpenAI HTTP adapter에 실제 metrics observer를 주입한다`() {
        ApplicationContextRunner()
            .withBean(SimpleMeterRegistry::class.java)
            .withBean(OpenAiTokenUsageMetrics::class.java)
            .withBean(OpenAiCloudLlm::class.java)
            .withBean(OpenAiConversationEmbeddingAdapter::class.java)
            .withPropertyValues("central.cloud.openai-api-key=test-key")
            .run { context ->
                assertThat(context.startupFailure).isNull()
                val metrics = context.getBean(OpenAiTokenUsageMetrics::class.java)
                assertThat(
                    ReflectionTestUtils.getField(context.getBean(OpenAiCloudLlm::class.java), "usageObserver"),
                ).isSameAs(metrics)
                assertThat(
                    ReflectionTestUtils.getField(
                        context.getBean(OpenAiConversationEmbeddingAdapter::class.java),
                        "usageObserver",
                    ),
                ).isSameAs(metrics)
            }
    }

    @Test
    fun `purpose별 호출 수와 cache 토큰을 분리해 기록한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = OpenAiTokenUsageMetrics(registry)

        metrics.recordAttempt(
            model = "gpt-5.6-luna",
            purpose = CloudLlmPurpose.NIA_JUDGE,
            requestPayloadChars = 42_000,
            cacheWriteRequested = false,
        )
        metrics.record(
            model = "gpt-5.6-luna",
            purpose = CloudLlmPurpose.NIA_JUDGE,
            usage =
                CloudLlmUsage(
                    promptTokens = 1_000,
                    completionTokens = 120,
                    cachedPromptTokens = 800,
                    cacheWritePromptTokens = 100,
                ),
        )

        assertThat(counter(registry, "central_openai_requests_total", "purpose", "nia_judge")).isEqualTo(1.0)
        assertThat(tokenCounter(registry, "input_total")).isEqualTo(1_000.0)
        assertThat(tokenCounter(registry, "uncached_input")).isEqualTo(100.0)
        assertThat(tokenCounter(registry, "output")).isEqualTo(120.0)
        assertThat(tokenCounter(registry, "cached_input")).isEqualTo(800.0)
        assertThat(tokenCounter(registry, "cache_write")).isEqualTo(100.0)
        assertThat(
            registry
                .get("central_openai_request_payload_chars")
                .tag("purpose", "nia_judge")
                .summary()
                .totalAmount(),
        ).isEqualTo(42_000.0)
        assertThat(
            counter(registry, "central_openai_cache_policy_requests_total", "policy", "disabled"),
        ).isEqualTo(1.0)
    }

    @Test
    fun `conversation RAG embedding 호출과 입력 토큰도 목적별로 기록한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = OpenAiTokenUsageMetrics(registry)

        metrics.recordAttempt("text-embedding-3-small", requestPayloadChars = 87)
        metrics.record(model = "text-embedding-3-small", promptTokens = 321)

        assertThat(
            counter(
                registry,
                "central_openai_requests_total",
                "purpose",
                OpenAiTokenUsageMetrics.EMBEDDING_PURPOSE,
            ),
        ).isEqualTo(1.0)
        assertThat(
            registry
                .get("central_openai_tokens_total")
                .tag("purpose", OpenAiTokenUsageMetrics.EMBEDDING_PURPOSE)
                .tag("category", "input_total")
                .counter()
                .count(),
        ).isEqualTo(321.0)
        assertThat(
            registry
                .get("central_openai_tokens_total")
                .tag("purpose", OpenAiTokenUsageMetrics.EMBEDDING_PURPOSE)
                .tag("category", "uncached_input")
                .counter()
                .count(),
        ).isEqualTo(321.0)
    }

    @Test
    fun `purpose별 OpenAI 지표가 Prometheus scrape 본문에 노출된다`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metrics = OpenAiTokenUsageMetrics(registry)

        metrics.recordAttempt(
            model = "gpt-5.6-luna",
            purpose = CloudLlmPurpose.NIA_SPEECH,
            requestPayloadChars = 9_999,
            cacheWriteRequested = true,
        )
        metrics.record(
            model = "gpt-5.6-luna",
            purpose = CloudLlmPurpose.NIA_SPEECH,
            usage = CloudLlmUsage(promptTokens = 77, completionTokens = 12),
        )

        assertThat(registry.scrape())
            .contains(
                "central_openai_requests_total{model=\"gpt-5.6-luna\",purpose=\"nia_speech\"} 1.0",
                "central_openai_cache_policy_requests_total{model=\"gpt-5.6-luna\",policy=\"explicit_prefix\",purpose=\"nia_speech\"} 1.0",
                "central_openai_request_payload_chars_count{model=\"gpt-5.6-luna\",purpose=\"nia_speech\"} 1",
                "central_openai_request_payload_chars_sum{model=\"gpt-5.6-luna\",purpose=\"nia_speech\"} 9999.0",
                "central_openai_tokens_total{category=\"input_total\",model=\"gpt-5.6-luna\",purpose=\"nia_speech\"} 77.0",
                "central_openai_tokens_total{category=\"uncached_input\",model=\"gpt-5.6-luna\",purpose=\"nia_speech\"} 77.0",
                "central_openai_tokens_total{category=\"output\",model=\"gpt-5.6-luna\",purpose=\"nia_speech\"} 12.0",
            )
    }

    @Test
    fun `실제 Responses HTTP 호출이 목적별 Prometheus 시계열까지 도달한다`() {
        val response =
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"판단"}]}],"usage":{"input_tokens":91,"output_tokens":6,"input_tokens_details":{"cached_tokens":40,"cache_write_tokens":0}}}"""
                .toByteArray()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/responses") { exchange ->
                    exchange.requestBody.readBytes()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                start()
            }
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        try {
            val llm =
                OpenAiCloudLlm(
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    timeoutSeconds = 2,
                    maxRetries = 0,
                    promptCacheWritesEnabled = false,
                    usageObserver = OpenAiTokenUsageMetrics(registry),
                )

            val result =
                llm.generate(
                    prompt = "니아야",
                    model = "gpt-5.6-luna",
                    history = emptyList(),
                    thinking = null,
                    options = CloudLlmRequestOptions(purpose = CloudLlmPurpose.NIA_JUDGE),
                )

            assertThat(result.text).isEqualTo("판단")

            assertThat(registry.scrape())
                .contains(
                    "central_openai_requests_total{model=\"gpt-5.6-luna\",purpose=\"nia_judge\"} 1.0",
                    "central_openai_cache_policy_requests_total{model=\"gpt-5.6-luna\",policy=\"disabled\",purpose=\"nia_judge\"} 1.0",
                    "central_openai_tokens_total{category=\"input_total\",model=\"gpt-5.6-luna\",purpose=\"nia_judge\"} 91.0",
                    "central_openai_tokens_total{category=\"uncached_input\",model=\"gpt-5.6-luna\",purpose=\"nia_judge\"} 51.0",
                    "central_openai_tokens_total{category=\"cached_input\",model=\"gpt-5.6-luna\",purpose=\"nia_judge\"} 40.0",
                    "central_openai_tokens_total{category=\"output\",model=\"gpt-5.6-luna\",purpose=\"nia_judge\"} 6.0",
                )
        } finally {
            server.stop(0)
            registry.close()
        }
    }

    private fun tokenCounter(
        registry: SimpleMeterRegistry,
        category: String,
    ): Double = counter(registry, "central_openai_tokens_total", "category", category)

    private fun counter(
        registry: SimpleMeterRegistry,
        name: String,
        tag: String,
        value: String,
    ): Double =
        registry
            .get(name)
            .tag(tag, value)
            .counter()
            .count()
}
