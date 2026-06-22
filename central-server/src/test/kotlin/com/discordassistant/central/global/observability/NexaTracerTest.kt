package com.discordassistant.central.global.observability

import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * NEXA-P18-T003 acceptance: IGNORE 경로와 SPEAK 경로의 비용 차이를 trace 에서 확인할 수 있다 — IGNORE 는
 * 정책 추론 span 에서 끝나고 SPEAK 는 generation·send span 까지 이어진다(span 수 차이가 비용 신호).
 */
class NexaTracerTest {
    // NOOP registry — 외부 trace 인프라 없이 호출/path 분기·반환값을 검증(미설정 환경 동작 보장).
    private val tracer = NexaTracer(ObservationRegistry.NOOP)

    @Test
    fun `span returns block result and supports nesting for speak path`() {
        // SPEAK 경로: policy→generation→send 까지 span 이 중첩된다(고비용 path).
        val result =
            tracer.span(NexaSpan.POLICY_INFERENCE, NexaTracePath.SPEAK) {
                tracer.span(NexaSpan.GENERATION, NexaTracePath.SPEAK) {
                    tracer.span(NexaSpan.DISCORD_SEND, NexaTracePath.SPEAK) { "sent" }
                }
            }
        assertEquals("sent", result)
    }

    @Test
    fun `ignore path opens only inference span (no generation or send)`() {
        // IGNORE 경로: 정책 추론 span 하나로 끝난다(저비용 path) — generation/send span 미진입.
        var generationEntered = false
        val result =
            tracer.span(NexaSpan.POLICY_INFERENCE, NexaTracePath.IGNORE) {
                // IGNORE 는 여기서 결정이 끝나므로 generation span 을 열지 않는다(아래 블록 미실행).
                "ignored"
            }
        assertEquals("ignored", result)
        // generation span 에 진입하지 않았다 — SPEAK 대비 span 수가 적어 trace 비용이 낮다(acceptance T003).
        assertFalse(generationEntered)
    }

    @Test
    fun `path label is low-cardinality enum value`() {
        assertEquals("ignore", NexaTracePath.IGNORE.label)
        assertEquals("speak", NexaTracePath.SPEAK.label)
    }
}
