package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * NEXA-P18-T007 acceptance: 길드/모델/purpose 별 token 과 추정 비용을 집계하되, **개별 사용자 ID 를 metric label 로
 * 사용하지 않는다**.
 */
class GlmCostMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = GlmCostMetrics(registry)

    @Test
    fun `aggregates tokens and cost by guild model purpose`() {
        metrics.recordUsage(
            guildPseudonym = "g-abc",
            model = "glm-5.1",
            purpose = "NEXA_SPEECH",
            promptTokens = 100,
            completionTokens = 40,
            costMicros = 2500,
        )

        assertEquals(
            100.0,
            registry
                .find("nexa_glm_tokens_total")
                .tag("guild", "g-abc")
                .tag("model", "glm-5.1")
                .tag("purpose", "NEXA_SPEECH")
                .tag("direction", "prompt")
                .counter()!!
                .count(),
        )
        assertEquals(
            40.0,
            registry
                .find("nexa_glm_tokens_total")
                .tag("direction", "completion")
                .counter()!!
                .count(),
        )
        assertEquals(
            2500.0,
            registry
                .find("nexa_glm_cost_micros_total")
                .tag("guild", "g-abc")
                .counter()!!
                .count(),
        )
    }

    @Test
    fun `does not use individual user id as a label`() {
        metrics.recordUsage("g-abc", "glm-5.1", "NEXA_SPEECH", 1, 1, 1)
        val forbidden = setOf("user", "userId", "userPseudonym", "authorId", "message", "messageId", "channel", "channelId")
        registry.meters.forEach { m ->
            m.id.tags.forEach { t ->
                assertTrue(t.key !in forbidden, "개별 사용자/원문 라벨 금지: ${m.id.name}{${t.key}}")
            }
        }
    }

    @Test
    fun `rejects negative values`() {
        assertThrows<IllegalArgumentException> { metrics.recordUsage("g", "m", "p", -1, 0, 0) }
    }
}
