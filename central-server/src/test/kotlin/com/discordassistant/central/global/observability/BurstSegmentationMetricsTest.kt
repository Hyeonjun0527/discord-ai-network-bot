package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P04-T024 acceptance: 버스트 관측 메트릭이 평균 fragment 수·gap·종료 이유·correction rate 를 원문 없이
 * 기록하고, guild/channel 등 고카디널리티 ID 를 metric label 로 직접 노출하지 않는다.
 */
class BurstSegmentationMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = BurstSegmentationMetrics(registry)

    @Test
    fun `records fragment count, gap, and reason without high-cardinality id labels`() {
        metrics.recordFinalized(fragmentCount = 4, gapMillis = 1000, reason = BurstTerminationOutcome.GAP_ELAPSED)
        metrics.recordFinalized(fragmentCount = 1, gapMillis = 7000, reason = BurstTerminationOutcome.OTHER_AUTHOR_INTRUSION)

        val fragmentSummary = registry.find("nexa_burst_fragment_count").summary()!!
        assertEquals(2L, fragmentSummary.count())
        assertEquals(5.0, fragmentSummary.totalAmount()) // 4 + 1, 평균 2.5.

        val gapSummary = registry.find("nexa_burst_gap_millis").summary()!!
        assertEquals(2L, gapSummary.count())

        assertEquals(
            1.0,
            registry
                .find("nexa_burst_finalized_total")
                .tag("reason", "gap_elapsed")
                .counter()!!
                .count(),
        )

        // 어떤 메트릭도 guild/channel/burst/message ID 를 label 로 노출하지 않는다(저카디널리티 reason 만 허용).
        val forbiddenLabelKeys = setOf("guild", "guildId", "channel", "channelId", "burst", "burstId", "message", "messageId")
        registry.meters.forEach { meter ->
            meter.id.tags.forEach { tag ->
                assertTrue(
                    tag.key !in forbiddenLabelKeys,
                    "고카디널리티 ID 라벨 노출 금지: ${meter.id.name}{${tag.key}}",
                )
            }
        }
    }

    @Test
    fun `correction rate is derivable from corrected over finalized counters`() {
        repeat(10) { metrics.recordFinalized(fragmentCount = 2, gapMillis = 500, reason = BurstTerminationOutcome.STREAM_END) }
        repeat(3) { metrics.recordCorrection() }

        val finalized = registry.find("nexa_burst_finalized_total").counters().sumOf { it.count() }
        val corrected = registry.find("nexa_burst_corrected_total").counter()!!.count()
        assertEquals(10.0, finalized)
        assertEquals(3.0, corrected)
        assertEquals(0.3, corrected / finalized) // correction rate = 3/10.
    }

    @Test
    fun `rejects empty burst finalization`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            metrics.recordFinalized(fragmentCount = 0, gapMillis = 0, reason = BurstTerminationOutcome.STREAM_END)
        }
    }
}
