package com.discordassistant.central.platform.discord.nexa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AdaptiveTurnBoundaryPolicyTest {
    private val policy = AdaptiveTurnBoundaryPolicy()
    private val start = Instant.parse("2026-07-25T00:00:00Z")

    @Test
    fun `표본이 없으면 2초와 7초의 중간 idle을 사용한다`() {
        assertThat(policy.adaptiveIdle(emptyList()).toMillis()).isEqualTo(4_500)
        assertThat(policy.deadline(start, start, emptyList(), null)).isEqualTo(start.plusMillis(4_500))
    }

    @Test
    fun `최근 간격 중앙값을 2초에서 7초 사이로 clamp한다`() {
        assertThat(policy.adaptiveIdle(listOf(300, 800, 1_000)).toMillis()).isEqualTo(2_000)
        assertThat(policy.adaptiveIdle(listOf(3_000, 5_000, 7_000)).toMillis()).isEqualTo(5_000)
        assertThat(policy.adaptiveIdle(listOf(8_000, 20_000)).toMillis()).isEqualTo(7_000)
    }

    @Test
    fun `typing은 idle보다 늦을 때만 연장하고 최초 메시지 30초를 넘지 않는다`() {
        val typingUntil = start.plusSeconds(40)

        assertThat(policy.deadline(start, start.plusSeconds(3), listOf(2_000), typingUntil))
            .isEqualTo(start.plusSeconds(30))
    }
}
