package com.discordassistant.central.routing

import com.discordassistant.central.routing.adapter.outbound.InMemoryChannelTokenBudgetAdapter
import com.discordassistant.central.routing.application.ChannelTokenBudgetLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class InMemoryChannelTokenBudgetAdapterTest {
    @Test
    fun `예약은 채널별로 원자 차감되고 동일 id는 중복 차감하지 않는다`() {
        val budget = InMemoryChannelTokenBudgetAdapter(fixedClock())
        val limits = ChannelTokenBudgetLimits(perChannel = 100, windowSeconds = 60)

        assertThat(budget.reserve("r1", "channel-a", 70, limits)).isTrue()
        assertThat(budget.reserve("r1", "channel-a", 70, limits)).isTrue()
        assertThat(budget.reserve("r2", "channel-a", 31, limits)).isFalse()
        assertThat(budget.reserve("r3", "channel-b", 100, limits)).isTrue()
        assertThat(budget.usedTokens("channel-a")).isEqualTo(70)
        assertThat(budget.usedTokens("channel-b")).isEqualTo(100)
    }

    @Test
    fun `성공 usage 정산은 예상량을 실제 input output 합계로 교체한다`() {
        val budget = InMemoryChannelTokenBudgetAdapter(fixedClock())
        val limits = ChannelTokenBudgetLimits(perChannel = 100, windowSeconds = 60)

        assertThat(budget.reserve("r1", "channel-a", 90, limits)).isTrue()
        assertThat(budget.settle("r1", 25)).isTrue()
        assertThat(budget.usedTokens("channel-a")).isEqualTo(25)
        assertThat(budget.reserve("r2", "channel-a", 75, limits)).isTrue()
        assertThat(budget.usedTokens("channel-a")).isEqualTo(100)
    }

    @Test
    fun `정산하지 않은 실패 예약은 window가 끝날 때까지 비용 안전량으로 남는다`() {
        val clock = MutableClock(Instant.parse("2026-07-25T00:00:00Z"))
        val budget = InMemoryChannelTokenBudgetAdapter(clock)
        val limits = ChannelTokenBudgetLimits(perChannel = 100, windowSeconds = 60)

        assertThat(budget.reserve("timeout", "channel-a", 100, limits)).isTrue()
        assertThat(budget.reserve("blocked", "channel-a", 1, limits)).isFalse()

        clock.advance(Duration.ofSeconds(61))

        assertThat(budget.reserve("fresh", "channel-a", 100, limits)).isTrue()
        assertThat(budget.usedTokens("channel-a")).isEqualTo(100)
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)

    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }
}
