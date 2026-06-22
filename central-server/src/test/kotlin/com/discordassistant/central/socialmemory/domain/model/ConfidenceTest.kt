package com.discordassistant.central.socialmemory.domain.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** NEXA-P07-T010: 출처별 기본 신뢰·감쇠. GLM 단일 추출은 확정 사실이 되지 않는다. */
class ConfidenceTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    @Test
    fun `acceptance - GLM 한 번의 추출은 확정 사실이 아니다`() {
        val glm = Confidence.forEvidence(MemoryEvidence.GLM_EXTRACTION)
        assertFalse(glm.isCertain)
        // 명시적 Discord 이벤트는 더 높다.
        val explicit = Confidence.forEvidence(MemoryEvidence.EXPLICIT_DISCORD_EVENT)
        assertTrue(explicit.value > glm.value)
        assertTrue(explicit.isCertain)
    }

    @Test
    fun `반복 관찰은 신뢰를 높이되 1_0 으로 단정하지 않는다`() {
        var c = Confidence.forEvidence(MemoryEvidence.GLM_EXTRACTION)
        repeat(20) { c = c.reinforced() }
        assertTrue(c.value < 1.0)
        assertTrue(c.value > Confidence.forEvidence(MemoryEvidence.GLM_EXTRACTION).value)
    }

    @Test
    fun `감쇠는 시간이 지날수록 신뢰를 낮춘다`() {
        val c = Confidence(0.8)
        val decayed = c.decayed(observedAt = t0, now = t0.plus(Duration.ofDays(30)), halfLife = Duration.ofDays(30))
        // 반감기 1회 → 약 절반.
        assertTrue(decayed.value < c.value)
        assertTrue(decayed.value in 0.35..0.45)
    }

    @Test
    fun `범위 밖 값은 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { Confidence(1.5) }
        assertThrows(IllegalArgumentException::class.java) { Confidence(-0.1) }
    }
}
