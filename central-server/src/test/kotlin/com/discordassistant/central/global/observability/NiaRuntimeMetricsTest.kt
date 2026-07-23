package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaRuntimeMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = NiaRuntimeMetrics(registry)

    @Test
    fun `수신 큐와 판단 종결을 고정 label로 집계한다`() {
        metrics.recordIngress(NiaIngressSource.MESSAGE)
        metrics.recordAdmission(NiaDispatchEvent.RECEIVE_CONTEXT, NiaDispatchOutcome.ACCEPTED_TO_MUTATION_OVERFLOW)
        metrics.recordAdmission(NiaDispatchEvent.RECEIVE_EVALUATION, NiaDispatchOutcome.REJECTED)
        metrics.recordTurn(
            NiaTurnMetricOutcome.SUPERSEDED,
            NiaSupersessionMetricStage.BEFORE_JUDGE,
            NiaTurnAddressing.EXPLICIT,
        )

        assertThat(
            registry
                .get("nexa_event_ingested_total")
                .tag("source", "message")
                .counter()
                .count(),
        ).isEqualTo(1.0)
        assertThat(
            registry
                .get("nexa_discord_event_admission_total")
                .tags("event", "receive_context", "outcome", "accepted_to_mutation_overflow")
                .counter()
                .count(),
        ).isEqualTo(1.0)
        assertThat(
            registry
                .get("nexa_discord_event_admission_total")
                .tags("event", "receive_evaluation", "outcome", "rejected")
                .counter()
                .count(),
        ).isEqualTo(1.0)
        assertThat(
            registry
                .get("nexa_turn_outcome_total")
                .tags("outcome", "superseded", "stage", "before_judge", "addressing", "explicit")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }
}
