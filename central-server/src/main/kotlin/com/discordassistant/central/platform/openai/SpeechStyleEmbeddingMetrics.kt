package com.discordassistant.central.platform.openai

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** 원문 없이 Speech 말투 RAG의 OpenAI embedding 호출량만 목적별로 집계한다. */
interface SpeechStyleEmbeddingMetrics {
    fun recordAttempt(
        model: String,
        inputChars: Int,
    )

    fun recordInputTokens(
        model: String,
        inputTokens: Int,
    )

    object Noop : SpeechStyleEmbeddingMetrics {
        override fun recordAttempt(
            model: String,
            inputChars: Int,
        ) = Unit

        override fun recordInputTokens(
            model: String,
            inputTokens: Int,
        ) = Unit
    }
}

@Component
class MicrometerSpeechStyleEmbeddingMetrics(
    private val meter: MeterRegistry,
) : SpeechStyleEmbeddingMetrics {
    override fun recordAttempt(
        model: String,
        inputChars: Int,
    ) {
        meter
            .counter(
                "central_openai_requests_total",
                "model",
                model,
                "purpose",
                PURPOSE,
            ).increment()
        if (inputChars > 0) {
            meter
                .summary(
                    "central_openai_request_payload_chars",
                    "model",
                    model,
                    "purpose",
                    PURPOSE,
                ).record(inputChars.toDouble())
        }
    }

    override fun recordInputTokens(
        model: String,
        inputTokens: Int,
    ) {
        if (inputTokens <= 0) return
        meter
            .counter(
                "central_openai_tokens_total",
                "model",
                model,
                "purpose",
                PURPOSE,
                "category",
                "input_total",
            ).increment(inputTokens.toDouble())
    }

    companion object {
        const val PURPOSE: String = "nia_speech_style_embedding"
    }
}
