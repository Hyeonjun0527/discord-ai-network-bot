package com.discordassistant.central.participation.application.rollout

import com.discordassistant.central.actionruntime.support.MutableTestClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * NEXA-P18-T023: [CanarySignalCollector] 의 길드별 1시간 슬라이딩 창·스냅샷·idle evict 검증.
 *
 * complaints·modelMismatches 는 공급원 미wiring(후속)이라 항상 0 임을 함께 문서화한다.
 */
class CanarySignalCollectorTest {
    private val clock = MutableTestClock(Instant.parse("2026-06-22T00:00:00Z"))
    private val collector = CanarySignalCollector(clock)

    @Test
    fun `snapshot counts per-guild events within the window`() {
        repeat(3) { collector.recordUtterance("g-1") }
        collector.recordStaleSend("g-1")
        collector.recordPrivacyError("g-1")
        collector.recordUtterance("g-2")

        val g1 = collector.snapshot("g-1")
        assertThat(g1.utterancesPerHour).isEqualTo(3)
        assertThat(g1.staleSends).isEqualTo(1)
        assertThat(g1.privacyErrors).isEqualTo(1)
        // 공급원 미wiring(후속) — 항상 0.
        assertThat(g1.complaints).isEqualTo(0)
        assertThat(g1.modelMismatches).isEqualTo(0)

        assertThat(collector.snapshot("g-2").utterancesPerHour).isEqualTo(1)
        assertThat(collector.activeGuilds()).containsExactlyInAnyOrder("g-1", "g-2")
    }

    @Test
    fun `events older than one hour fall out of the window`() {
        collector.recordUtterance("g-1") // t=0
        clock.advance(Duration.ofMinutes(30))
        collector.recordUtterance("g-1") // t=30m
        // t=61m: 첫 이벤트(t=0)는 창 밖, 두 번째(t=30m)만 남는다.
        clock.advance(Duration.ofMinutes(31))

        assertThat(collector.snapshot("g-1").utterancesPerHour).isEqualTo(1)
    }

    @Test
    fun `guild with all events aged out is evicted from active set`() {
        collector.recordUtterance("g-1")
        clock.advance(Duration.ofMinutes(61))

        assertThat(collector.snapshot("g-1").utterancesPerHour).isEqualTo(0)
        assertThat(collector.activeGuilds()).doesNotContain("g-1")
    }

    @Test
    fun `unknown guild snapshot is all zero`() {
        val s = collector.snapshot("never-seen")
        assertThat(s.utterancesPerHour).isEqualTo(0)
        assertThat(s.staleSends).isEqualTo(0)
        assertThat(s.privacyErrors).isEqualTo(0)
    }
}
