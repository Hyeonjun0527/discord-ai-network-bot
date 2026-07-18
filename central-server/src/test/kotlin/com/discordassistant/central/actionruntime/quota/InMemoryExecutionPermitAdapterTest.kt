package com.discordassistant.central.actionruntime.quota

import com.discordassistant.central.actionruntime.adapter.outbound.quota.InMemoryExecutionPermitAdapter
import com.discordassistant.central.actionruntime.application.port.out.ExecutionLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class InMemoryExecutionPermitAdapterTest {
    private val store = InMemoryExecutionPermitAdapter(Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC))
    private val limits = ExecutionLimits(perChannel = 1, global = 2)

    @Test
    fun `동일 action 재시도는 중복 차감하지 않고 channel과 global은 함께 거부된다`() {
        assertThat(store.reserve("a1", "c1", limits)).isTrue()
        assertThat(store.reserve("a1", "c1", limits)).isTrue()
        assertThat(store.reserve("a2", "c1", limits)).isFalse()
        assertThat(store.reserve("a2", "c2", limits)).isTrue()
        assertThat(store.reserve("a3", "c3", limits)).isFalse()
    }

    @Test
    fun `영속 실패 해제 뒤 같은 channel이 다시 예약된다`() {
        assertThat(store.reserve("a1", "c1", limits)).isTrue()
        assertThat(store.release("a1")).isTrue()
        assertThat(store.reserve("a2", "c1", limits)).isTrue()
    }

    @Test
    fun `같은 window의 늦은 예약도 counter와 함께 만료되어 새 window를 차감하지 않는다`() {
        val clock = MutableClock(Instant.parse("2026-07-17T00:00:00Z"))
        val permits = InMemoryExecutionPermitAdapter(clock)
        val wideLimits = ExecutionLimits(perChannel = 3, global = 3, windowSeconds = 60)
        assertThat(permits.reserve("a1", "c1", wideLimits)).isTrue()
        clock.advance(Duration.ofSeconds(50))
        assertThat(permits.reserve("a2", "c1", wideLimits)).isTrue()

        clock.advance(Duration.ofSeconds(11))
        assertThat(permits.reserve("fresh", "c1", wideLimits)).isTrue()
        assertThat(permits.release("a2")).isFalse()
        assertThat(permits.reserve("fresh-2", "c1", wideLimits)).isTrue()
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
