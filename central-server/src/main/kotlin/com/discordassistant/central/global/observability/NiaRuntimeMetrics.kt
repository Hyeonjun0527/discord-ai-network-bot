package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** NIA 수신부터 판단 종결까지의 비용 분모와 유실 여부를 원문 없이 집계한다. */
@Component
class NiaRuntimeMetrics(
    private val meter: MeterRegistry,
) {
    fun recordIngress(source: NiaIngressSource) {
        meter.counter("nexa_event_ingested_total", "source", source.label).increment()
    }

    fun recordAdmission(
        event: NiaDispatchEvent,
        outcome: NiaDispatchOutcome,
    ) {
        meter
            .counter(
                "nexa_discord_event_admission_total",
                "event",
                event.label,
                "outcome",
                outcome.label,
            ).increment()
    }

    fun recordTurn(
        outcome: NiaTurnMetricOutcome,
        supersessionStage: NiaSupersessionMetricStage = NiaSupersessionMetricStage.NONE,
        addressing: NiaTurnAddressing,
    ) {
        meter
            .counter(
                "nexa_turn_outcome_total",
                "outcome",
                outcome.label,
                "stage",
                supersessionStage.label,
                "addressing",
                addressing.label,
            ).increment()
    }
}

enum class NiaIngressSource(
    val label: String,
) {
    MESSAGE("message"),
    EDIT("edit"),
    DELETE("delete"),
}

enum class NiaDispatchEvent(
    val label: String,
) {
    RECEIVE_CONTEXT("receive_context"),
    RECEIVE_EVALUATION("receive_evaluation"),
    EDIT("edit"),
    DELETE("delete"),
}

enum class NiaDispatchOutcome(
    val label: String,
) {
    ACCEPTED("accepted"),
    ACCEPTED_AFTER_EVICTION("accepted_after_eviction"),
    ACCEPTED_TO_MUTATION_OVERFLOW("accepted_to_mutation_overflow"),
    REJECTED("rejected"),
}

enum class NiaTurnMetricOutcome(
    val label: String,
) {
    INACTIVE("inactive"),
    NOT_SPEAKING("not_speaking"),
    RULE_SILENT("rule_silent"),
    RULE_WAIT("rule_wait"),
    SCHEDULING_REJECTED("scheduling_rejected"),
    ATTENTION_DEFERRED("attention_deferred"),
    SUPERSEDED("superseded"),
    SHADOW_PREDICTED("shadow_predicted"),
    EMITTED("emitted"),
    FAILED("failed"),
}

enum class NiaSupersessionMetricStage(
    val label: String,
) {
    NONE("none"),
    BEFORE_JUDGE("before_judge"),
    AFTER_JUDGE("after_judge"),
    BEFORE_SPEECH_GENERATION("before_speech_generation"),
    BEFORE_SCHEDULE("before_schedule"),
}

enum class NiaTurnAddressing(
    val label: String,
) {
    UNCLASSIFIED("unclassified"),
    AMBIENT("ambient"),
    EXPLICIT("explicit"),
    CONTINUATION("continuation"),
    REEVALUATION("reevaluation"),
}
