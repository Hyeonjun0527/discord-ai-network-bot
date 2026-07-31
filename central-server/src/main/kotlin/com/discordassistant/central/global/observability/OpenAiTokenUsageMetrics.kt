package com.discordassistant.central.global.observability

import com.discordassistant.central.routing.application.CloudLlmPurpose
import com.discordassistant.central.routing.application.CloudLlmUsage
import com.discordassistant.central.routing.application.CloudLlmUsageObserver
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** OpenAI 요청 크기와 provider가 보고한 token을 원문 없이 목적별로 집계한다. */
@Component
class OpenAiTokenUsageMetrics(
    private val meter: MeterRegistry,
) : CloudLlmUsageObserver {
    override fun recordAttempt(
        model: String,
        purpose: CloudLlmPurpose,
    ) = recordRequest(model, purpose.name.lowercase())

    override fun recordAttempt(
        model: String,
        purpose: CloudLlmPurpose,
        requestPayloadChars: Int,
        cacheWriteRequested: Boolean,
    ) {
        val normalizedPurpose = purpose.name.lowercase()
        recordRequest(model, normalizedPurpose)
        recordPayloadSize(model, normalizedPurpose, requestPayloadChars)
        meter
            .counter(
                "central_openai_cache_policy_requests_total",
                "model",
                model,
                "purpose",
                normalizedPurpose,
                "policy",
                if (cacheWriteRequested) "explicit_prefix" else "disabled",
            ).increment()
    }

    override fun record(
        model: String,
        purpose: CloudLlmPurpose,
        usage: CloudLlmUsage,
    ) {
        val normalizedPurpose = purpose.name.lowercase()
        record(model, normalizedPurpose, "input_total", usage.promptTokens)
        record(
            model,
            normalizedPurpose,
            "uncached_input",
            (usage.promptTokens - usage.cachedPromptTokens - usage.cacheWritePromptTokens).coerceAtLeast(0),
        )
        record(model, normalizedPurpose, "output", usage.completionTokens)
        record(model, normalizedPurpose, "cached_input", usage.cachedPromptTokens)
        record(model, normalizedPurpose, "cache_write", usage.cacheWritePromptTokens)
    }

    private fun recordRequest(
        model: String,
        purpose: String,
    ) {
        meter
            .counter(
                "central_openai_requests_total",
                "model",
                model,
                "purpose",
                purpose,
            ).increment()
    }

    private fun recordPayloadSize(
        model: String,
        purpose: String,
        requestPayloadChars: Int,
    ) {
        if (requestPayloadChars <= 0) return
        meter
            .summary(
                "central_openai_request_payload_chars",
                "model",
                model,
                "purpose",
                purpose,
            ).record(requestPayloadChars.toDouble())
    }

    private fun record(
        model: String,
        purpose: String,
        category: String,
        tokens: Int,
    ) {
        if (tokens <= 0) return
        meter
            .counter(
                "central_openai_tokens_total",
                "model",
                model,
                "purpose",
                purpose,
                "category",
                category,
            ).increment(tokens.toDouble())
    }
}
