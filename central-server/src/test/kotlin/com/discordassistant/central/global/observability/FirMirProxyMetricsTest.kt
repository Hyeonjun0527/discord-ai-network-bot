package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * NEXA-P18-T008 acceptance: shadow/canary outcome 에서 지연 집계한 false interruption(과반응)·missed intervention
 * (과침묵) proxy 를 운영 경보로 노출하되, **proxy 임을 명시**(metric 이름에 proxy)하고 사용자 심리를 사실로
 * 표시하지 않는다(집계만).
 */
class FirMirProxyMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = FirMirProxyMetrics(registry)

    @Test
    fun `records proxy counts and rates from a delayed aggregation batch`() {
        // 표본 100 중 FIR 5, MIR 8.
        metrics.recordBatch(sampleCount = 100, falseInterruptionCount = 5, missedInterventionCount = 8)

        assertEquals(5.0, registry.find("nexa_fir_proxy_total").counter()!!.count())
        assertEquals(8.0, registry.find("nexa_mir_proxy_total").counter()!!.count())
        assertEquals(0.05, metrics.falseInterruptionRate())
        assertEquals(0.08, metrics.missedInterventionRate())
        assertEquals(0.05, registry.find("nexa_fir_proxy_rate").gauge()!!.value())
    }

    @Test
    fun `metric names declare proxy to avoid asserting user psychology as fact`() {
        metrics.recordBatch(10, 1, 1)
        // 두 핵심 지표 이름에 proxy 가 들어가야 한다(dashboard 가 proxy 배지로 표시).
        assertTrue(registry.find("nexa_fir_proxy_rate").gauge() != null)
        assertTrue(registry.find("nexa_mir_proxy_rate").gauge() != null)
    }

    @Test
    fun `zero samples yields zero rate (no assertion)`() {
        metrics.recordBatch(0, 0, 0)
        assertEquals(0.0, metrics.falseInterruptionRate())
    }

    @Test
    fun `rejects proxy count exceeding sample count`() {
        assertThrows<IllegalArgumentException> {
            metrics.recordBatch(
                sampleCount = 1,
                falseInterruptionCount = 2,
                missedInterventionCount = 0,
            )
        }
    }
}
