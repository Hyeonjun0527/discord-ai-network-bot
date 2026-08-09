package com.discordassistant.central.speech.application.humanstyle

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** 원문·카드 ID 없이 Speech 말투 RAG가 참고 예시를 고른 결과만 저카디널리티 코드로 집계한다. */
interface HumanSpeechStyleRagMetrics {
    fun record(outcome: HumanSpeechStyleRagOutcome)

    object Noop : HumanSpeechStyleRagMetrics {
        override fun record(outcome: HumanSpeechStyleRagOutcome) = Unit
    }
}

@Component
class MicrometerHumanSpeechStyleRagMetrics(
    private val meter: MeterRegistry,
) : HumanSpeechStyleRagMetrics {
    override fun record(outcome: HumanSpeechStyleRagOutcome) {
        meter.counter("nexa_speech_style_rag_retrieval_total", "outcome", outcome.label).increment()
    }
}

enum class HumanSpeechStyleRagOutcome(
    val label: String,
) {
    DISABLED("disabled"),
    MISSING_RESPONSE_MODE("missing_response_mode"),
    NO_ENABLED_EXAMPLES("no_enabled_examples"),
    POLICY_ABSTAIN("policy_abstain"),
    EMBEDDING_UNAVAILABLE("embedding_unavailable"),
    NO_MATCH("no_match"),
    SELECTED("selected"),
}
