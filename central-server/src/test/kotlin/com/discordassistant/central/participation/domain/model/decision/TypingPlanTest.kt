package com.discordassistant.central.participation.domain.model.decision

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/** NEXA-P12-T014 TypingPlan 의 acceptance 단위 테스트(scheduler 계획·무한 typing 방지). */
class TypingPlanTest {
    @Test
    fun `acceptance — maxDuration 은 양수여야 한다 (무한 typing 방지)`() {
        assertThatThrownBy { TypingPlan(startOffset = Duration.ZERO, maxDuration = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            TypingPlan(startOffset = Duration.ZERO, maxDuration = Duration.ofSeconds(-1))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `acceptance — maxDuration 은 절대 상한을 넘을 수 없다 (무한 typing 구조적 방지)`() {
        assertThatThrownBy {
            TypingPlan(startOffset = Duration.ZERO, maxDuration = Duration.ofMinutes(10))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `acceptance — typing 은 startOffset+maxDuration 시점에 반드시 종료된다`() {
        val plan = TypingPlan(startOffset = Duration.ofSeconds(3), maxDuration = Duration.ofSeconds(5))
        assertThat(plan.mustEndBy).isEqualTo(Duration.ofSeconds(8))
        // mustEndBy 이상 경과면 만료(응답이 안 와도 종료) — 무한 typing 없음.
        assertThat(plan.isExpiredAt(Duration.ofSeconds(8))).isTrue()
        assertThat(plan.isExpiredAt(Duration.ofSeconds(9))).isTrue()
        assertThat(plan.isExpiredAt(Duration.ofSeconds(7))).isFalse()
    }

    @Test
    fun `forSpeak — 발사 직전 leadTime 동안만 typing 을 시작한다 (scheduler offset)`() {
        // 발사 10초 delay, lead 2초 → typing 은 8초 시점부터 시작.
        val plan = TypingPlan.forSpeak(fireDelay = Duration.ofSeconds(10), leadTime = Duration.ofSeconds(2))
        assertThat(plan.startOffset).isEqualTo(Duration.ofSeconds(8))
        // 유지 상한은 leadTime 이상(여기선 burstSpan=0 이라 2초).
        assertThat(plan.maxDuration).isEqualTo(Duration.ofSeconds(2))
    }

    @Test
    fun `forSpeak — delay 가 leadTime 보다 짧으면 0 부터 시작한다`() {
        val plan = TypingPlan.forSpeak(fireDelay = Duration.ofSeconds(1), leadTime = Duration.ofSeconds(2))
        assertThat(plan.startOffset).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `forSpeak — burst span 이 길어도 maxDuration 은 절대 상한으로 cap 된다 (무한 typing 방지)`() {
        val plan =
            TypingPlan.forSpeak(
                fireDelay = Duration.ofSeconds(1),
                leadTime = Duration.ofSeconds(2),
                burstSpan = Duration.ofMinutes(10),
            )
        assertThat(plan.maxDuration).isEqualTo(TypingPlan.MAX_TYPING_DURATION)
    }

    @Test
    fun `forSpeak — 블로킹 없이 상대 시간 값만 계산한다 (sleep 아님)`() {
        // 호출이 즉시 반환되고(블로킹 sleep 없음) 값이 상대 Duration 임을 확인.
        val start = System.nanoTime()
        val plan = TypingPlan.forSpeak(fireDelay = Duration.ofSeconds(5))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isLessThan(100) // 즉시 반환 — delay 만큼 자지 않는다.
        assertThat(plan.startOffset).isNotNull()
        assertThat(plan.maxDuration).isNotNull()
    }
}
