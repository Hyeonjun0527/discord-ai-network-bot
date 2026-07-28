package com.discordassistant.central.onboarding

import com.discordassistant.central.onboarding.adapter.outbound.quota.InMemoryNiaWebDemoQuotaAdapter
import com.discordassistant.central.onboarding.application.NiaWebDemoQuotaDecision
import com.discordassistant.central.onboarding.application.NiaWebDemoQuotaLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class NiaWebDemoQuotaAdapterTest {
    @Test
    fun `사용자 속도와 전체 한도를 원자적으로 적용한다`() {
        val quota = InMemoryNiaWebDemoQuotaAdapter()
        val limits =
            NiaWebDemoQuotaLimits(
                perMinute = 2,
                perUserWindow = 5,
                globalWindow = 3,
                windowSeconds = 86_400,
            )

        assertThat(quota.tryConsume("user-a", limits)).isEqualTo(NiaWebDemoQuotaDecision.Allowed(4))
        assertThat(quota.tryConsume("user-a", limits)).isEqualTo(NiaWebDemoQuotaDecision.Allowed(3))
        assertThat(quota.tryConsume("user-a", limits)).isEqualTo(NiaWebDemoQuotaDecision.PerMinuteExceeded)
        assertThat(quota.tryConsume("user-b", limits)).isEqualTo(NiaWebDemoQuotaDecision.Allowed(4))
        assertThat(quota.tryConsume("user-c", limits)).isEqualTo(NiaWebDemoQuotaDecision.GlobalWindowExceeded)
    }

    @Test
    fun `시간 창이 지나면 사용자와 전체 한도가 다시 열린다`() {
        val clock = MutableClock(Instant.parse("2026-07-29T00:00:00Z"))
        val quota = InMemoryNiaWebDemoQuotaAdapter(clock)
        val limits =
            NiaWebDemoQuotaLimits(
                perMinute = 1,
                perUserWindow = 1,
                globalWindow = 1,
                windowSeconds = 120,
            )

        assertThat(quota.tryConsume("user-a", limits)).isEqualTo(NiaWebDemoQuotaDecision.Allowed(0))
        clock.advanceSeconds(121)

        assertThat(quota.tryConsume("user-a", limits)).isEqualTo(NiaWebDemoQuotaDecision.Allowed(0))
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }
}
