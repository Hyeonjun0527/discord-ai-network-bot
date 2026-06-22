package com.discordassistant.central.global.observability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * NEXA-P18-T012 acceptance: policy timeout·schema mismatch·fallback-to-silent 비율을 경보하되, **fallback 자체가
 * 정상 안전 동작일 수 있어 지속 시간/비율 기준을 사용**한다(단일 이벤트로는 경보 안 함).
 */
class NexaModelErrorAlertEvaluatorTest {
    private val evaluator = NexaModelErrorAlertEvaluator()

    @Test
    fun `single fallback event does not alert`() {
        // 표본 1, fallback 1 — 비율 100% 라도 표본·지속 기준 미달이라 경보 없음.
        val alert =
            evaluator.evaluate(
                NexaModelErrorWindow(
                    totalRequests = 1,
                    policyTimeouts = 0,
                    schemaMismatches = 0,
                    fallbackToSilent = 1,
                    sustainedFor = Duration.ofSeconds(10),
                ),
            )
        assertNull(alert)
    }

    @Test
    fun `does not alert when ratio is high but not sustained`() {
        // 비율 ≥ 0.2, 표본 충분하지만 지속 < 5분 → 경보 없음(짧은 spike).
        val alert =
            evaluator.evaluate(
                NexaModelErrorWindow(
                    totalRequests = 100,
                    policyTimeouts = 30,
                    schemaMismatches = 0,
                    fallbackToSilent = 0,
                    sustainedFor = Duration.ofMinutes(1),
                ),
            )
        assertNull(alert)
    }

    @Test
    fun `alerts human-confirm when ratio and duration both exceed`() {
        // 비율 0.3 ≥ 0.2, 지속 8분 ≥ 5분, 둘 다 critical 미만 → 사람 확인.
        val alert =
            evaluator.evaluate(
                NexaModelErrorWindow(
                    totalRequests = 100,
                    policyTimeouts = 10,
                    schemaMismatches = 10,
                    fallbackToSilent = 10,
                    sustainedFor = Duration.ofMinutes(8),
                ),
            )
        assertNotNull(alert)
        assertEquals(NexaAlertResponse.HUMAN_CONFIRM, alert!!.response)
    }

    @Test
    fun `escalates to auto-downgrade on critical ratio`() {
        val alert =
            evaluator.evaluate(
                NexaModelErrorWindow(
                    totalRequests = 100,
                    policyTimeouts = 60,
                    schemaMismatches = 0,
                    fallbackToSilent = 0,
                    sustainedFor = Duration.ofMinutes(6),
                ),
            )
        assertNotNull(alert)
        assertEquals(NexaAlertResponse.AUTO_DOWNGRADE, alert!!.response)
    }

    @Test
    fun `does not alert below minimum samples`() {
        val alert =
            evaluator.evaluate(
                NexaModelErrorWindow(
                    totalRequests = 5,
                    policyTimeouts = 5,
                    schemaMismatches = 0,
                    fallbackToSilent = 0,
                    sustainedFor = Duration.ofMinutes(30),
                ),
            )
        assertNull(alert)
    }
}
