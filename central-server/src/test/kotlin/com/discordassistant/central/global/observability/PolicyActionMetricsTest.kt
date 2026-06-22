package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P18-T004 acceptance: IGNORE/WAIT/REACT/SPEAK/CANCEL raw·post-constraint 분포를 기록하고, constraint 가
 * action 을 변경한 비율을 별도 지표로 센다. 고카디널리티 ID label 미노출.
 */
class PolicyActionMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = PolicyActionMetrics(registry)

    @Test
    fun `records raw and final distribution separately`() {
        metrics.recordDecision(NexaActionLabel.SPEAK, NexaActionLabel.SPEAK)
        metrics.recordDecision(NexaActionLabel.IGNORE, NexaActionLabel.IGNORE)

        assertEquals(
            1.0,
            registry
                .find("nexa_policy_action_total")
                .tag("kind", "speak")
                .tag("stage", "raw")
                .counter()!!
                .count(),
        )
        assertEquals(
            1.0,
            registry
                .find("nexa_policy_action_total")
                .tag("kind", "ignore")
                .tag("stage", "final")
                .counter()!!
                .count(),
        )
    }

    @Test
    fun `constraint override is a separate metric counting only changed actions`() {
        // raw=SPEAK 인데 제약으로 final=IGNORE 로 바뀐 1건만 override.
        metrics.recordDecision(NexaActionLabel.SPEAK, NexaActionLabel.IGNORE)
        metrics.recordDecision(NexaActionLabel.SPEAK, NexaActionLabel.SPEAK) // 안 바뀜.

        assertEquals(1.0, registry.find("nexa_policy_constraint_overridden_total").counter()!!.count())
        // override 비율 = overridden / raw total = 1 / 2.
        val rawTotal =
            registry
                .find("nexa_policy_action_total")
                .tag("stage", "raw")
                .counters()
                .sumOf { it.count() }
        assertEquals(2.0, rawTotal)
    }

    @Test
    fun `does not expose high-cardinality id labels`() {
        metrics.recordDecision(NexaActionLabel.REACT, NexaActionLabel.REACT)
        val forbidden = setOf("guild", "guildId", "channel", "channelId", "user", "userId", "message", "messageId")
        registry.meters.forEach { m ->
            m.id.tags.forEach { t ->
                assertTrue(t.key !in forbidden, "고카디널리티 ID 라벨 금지: ${m.id.name}{${t.key}}")
            }
        }
    }
}
