package com.discordassistant.central.speech.humanstyle

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagOutcome
import com.discordassistant.central.speech.application.humanstyle.MicrometerHumanSpeechStyleRagMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HumanSpeechStyleRagMetricsTest {
    @Test
    fun `검색 outcome만 저카디널리티 label로 기록한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerHumanSpeechStyleRagMetrics(registry)

        metrics.record(HumanSpeechStyleRagOutcome.POLICY_ABSTAIN)
        metrics.record(HumanSpeechStyleRagOutcome.SELECTED)

        assertThat(
            registry
                .find("nexa_speech_style_rag_retrieval_total")
                .tag("outcome", "policy_abstain")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
        assertThat(
            registry
                .find("nexa_speech_style_rag_retrieval_total")
                .tag("outcome", "selected")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
        val forbiddenTags = setOf("card", "card_id", "channel", "channel_id", "message", "message_id", "source", "user", "user_id")
        registry.meters.forEach { meter ->
            meter.id.tags.forEach { tag -> assertThat(tag.key).isNotIn(forbiddenTags) }
        }
    }
}
