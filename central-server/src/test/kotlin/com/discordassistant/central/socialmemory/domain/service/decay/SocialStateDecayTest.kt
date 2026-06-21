package com.discordassistant.central.socialmemory.domain.service.decay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * NEXA-P06-T017 Clock 기반 감쇠 함수 테스트.
 *
 * acceptance: 시스템 시각 직접 접근 없이 시간 이동 테스트가 가능하다 — [now] 를 인자로 옮겨 반감기·최소값 곡선을
 * 결정론적으로 검증한다(Instant.now/System 미사용).
 */
class SocialStateDecayTest {
    private val last = Instant.parse("2026-01-01T00:00:00Z")
    private val policy = HalfLifeDecay(halfLife = Duration.ofDays(10))

    @Test
    fun `경과 0 이면 baseValue 그대로`() {
        assertEquals(0.8, SocialStateDecay.decayed(0.8, last, last, policy), 1e-12)
    }

    @Test
    fun `반감기 경과 시 절반으로 감쇠한다 (시간 이동)`() {
        val halfLater = last.plus(Duration.ofDays(10))
        assertEquals(0.4, SocialStateDecay.decayed(0.8, last, halfLater, policy), 1e-9)
        val twoHalfLives = last.plus(Duration.ofDays(20))
        assertEquals(0.2, SocialStateDecay.decayed(0.8, last, twoHalfLives, policy), 1e-9)
    }

    @Test
    fun `floor 아래로 떨어지지 않는다`() {
        val flooredPolicy = HalfLifeDecay(halfLife = Duration.ofDays(1), floor = 0.1)
        val farFuture = last.plus(Duration.ofDays(1000))
        assertEquals(0.1, SocialStateDecay.decayed(0.9, last, farFuture, flooredPolicy), 1e-12)
    }

    @Test
    fun `과거 시각(now가 last 이전)도 baseValue 를 넘지 않는다`() {
        val before = last.minus(Duration.ofDays(5))
        val v = SocialStateDecay.decayed(0.6, last, before, policy)
        assertTrue(v <= 0.6, "감쇠는 baseValue 를 초과하지 않는다")
    }

    @Test
    fun `잘못된 정책은 생성 시 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { HalfLifeDecay(halfLife = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { HalfLifeDecay(halfLife = Duration.ofDays(-1)) }
        assertThrows(IllegalArgumentException::class.java) { HalfLifeDecay(halfLife = Duration.ofDays(1), floor = 1.0) }
    }

    @Test
    fun `같은 정책과 시간 이동은 결정론적이다 (flakiness 없음)`() {
        val at = last.plus(Duration.ofDays(3))
        repeat(5) {
            assertEquals(
                SocialStateDecay.decayed(0.7, last, at, HalfLifeDecay.FAMILIARITY),
                SocialStateDecay.decayed(0.7, last, at, HalfLifeDecay.FAMILIARITY),
                1e-15,
            )
        }
    }
}
