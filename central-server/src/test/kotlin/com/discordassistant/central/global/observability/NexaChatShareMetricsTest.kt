package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * NEXA-P18-T005 acceptance: 최근 5분/1시간 human burst 대비 NEXA burst share 와 token/char share 를 함께 본다
 * (조각 수가 아닌 burst 수 기준). 고카디널리티 ID label 미노출.
 */
class NexaChatShareMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = NexaChatShareMetrics(registry)

    @Test
    fun `publishes burst and token share per window`() {
        // 5분 창: human 7 burst + nexa 3 burst → burst share 0.3. token 800 vs 200 → 0.2.
        metrics.publish(ShareWindow.FIVE_MIN, humanBursts = 7, nexaBursts = 3, humanTokens = 800, nexaTokens = 200)
        assertEquals(0.3, metrics.burstShare(ShareWindow.FIVE_MIN))
        assertEquals(0.2, metrics.tokenShare(ShareWindow.FIVE_MIN))

        val gauge = registry.find("nexa_chat_share_burst_ratio").tag("window", "5m").gauge()!!
        assertEquals(0.3, gauge.value())
    }

    @Test
    fun `burst and token share are tracked independently (not fragment count)`() {
        // burst 동률이라도 token share 는 다를 수 있다 — 둘을 함께 본다.
        metrics.publish(ShareWindow.ONE_HOUR, humanBursts = 1, nexaBursts = 1, humanTokens = 900, nexaTokens = 100)
        assertEquals(0.5, metrics.burstShare(ShareWindow.ONE_HOUR))
        assertEquals(0.1, metrics.tokenShare(ShareWindow.ONE_HOUR))
    }

    @Test
    fun `zero denominator yields zero share (no assertion)`() {
        metrics.publish(ShareWindow.FIVE_MIN, humanBursts = 0, nexaBursts = 0, humanTokens = 0, nexaTokens = 0)
        assertEquals(0.0, metrics.burstShare(ShareWindow.FIVE_MIN))
    }

    @Test
    fun `rejects negative counts`() {
        assertThrows<IllegalArgumentException> {
            metrics.publish(ShareWindow.FIVE_MIN, humanBursts = -1, nexaBursts = 0, humanTokens = 0, nexaTokens = 0)
        }
    }

    @Test
    fun `only low-cardinality window label is used`() {
        metrics.publish(ShareWindow.FIVE_MIN, 1, 1, 1, 1)
        val forbidden = setOf("guild", "guildId", "channel", "channelId", "user", "userId")
        registry.meters.forEach { m ->
            m.id.tags.forEach { t -> assertTrue(t.key !in forbidden, "고카디널리티 라벨 금지: ${m.id.name}{${t.key}}") }
        }
    }
}
