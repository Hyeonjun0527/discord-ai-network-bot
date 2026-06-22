package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * NEXA-P18-T006 acceptance: policy inference·schedule wait·generation·first/last bubble latency 를 분리하고,
 * **취소된 action 도** 취소까지 걸린 시간을 기록한다.
 */
class NexaLatencyMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = NexaLatencyMetrics(registry)

    @Test
    fun `records each latency stage separately`() {
        metrics.recordPolicyInference(12)
        metrics.recordScheduleWait(3000)
        metrics.recordGeneration(800)
        metrics.recordFirstBubble(900)
        metrics.recordLastBubble(1500)

        assertEquals(12.0, registry.find("nexa_latency_policy_inference_millis").summary()!!.totalAmount())
        assertEquals(3000.0, registry.find("nexa_latency_schedule_wait_millis").summary()!!.totalAmount())
        assertEquals(800.0, registry.find("nexa_latency_generation_millis").summary()!!.totalAmount())
        assertEquals(900.0, registry.find("nexa_latency_first_bubble_millis").summary()!!.totalAmount())
        assertEquals(1500.0, registry.find("nexa_latency_last_bubble_millis").summary()!!.totalAmount())
    }

    @Test
    fun `cancelled action records time to cancellation in a separate metric`() {
        metrics.recordCancelled(450)
        val cancelled = registry.find("nexa_latency_cancelled_millis").summary()!!
        assertEquals(1L, cancelled.count())
        assertEquals(450.0, cancelled.totalAmount())
    }

    @Test
    fun `rejects negative latency`() {
        assertThrows<IllegalArgumentException> { metrics.recordGeneration(-1) }
    }
}
