package com.discordassistant.central.speech.routing

import com.discordassistant.central.speech.adapter.outbound.routing.CloudCallBudget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** NEXA-P14-T014: 단일 호출 timeout budget — stale 응답 폐기. */
class CloudCallBudgetTest {
    private val now = Instant.parse("2026-06-22T00:00:00Z")

    @Test
    fun `isStale true when now reached or passed deadline`() {
        val budget = CloudCallBudget(Duration.ofSeconds(8), now.plusSeconds(10))
        assertThat(budget.isStale(now.plusSeconds(5))).isFalse()
        assertThat(budget.isStale(now.plusSeconds(10))).isTrue()
        assertThat(budget.isStale(now.plusSeconds(20))).isTrue()
    }

    @Test
    fun `until clamps timeout to remaining time`() {
        val budget = CloudCallBudget.until(now, now.plusSeconds(3), defaultTimeout = Duration.ofSeconds(8))
        assertThat(budget.perCallTimeout).isLessThanOrEqualTo(Duration.ofSeconds(3))
    }

    @Test
    fun `until falls back to default timeout when deadline already passed`() {
        val budget = CloudCallBudget.until(now, now.minusSeconds(5), defaultTimeout = Duration.ofSeconds(8))
        assertThat(budget.perCallTimeout).isEqualTo(Duration.ofSeconds(8))
    }
}
