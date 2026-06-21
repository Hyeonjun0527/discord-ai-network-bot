package com.discordassistant.central.participation.domain.model.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** NEXA-P06-T012: 감정으로 저장·노출하지 않음 + seed replay 결정성 + 시간 감쇠. */
class SocialEnergyTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    @Test
    fun `윤리 - isEmotion 은 항상 false (감정 아님)`() {
        assertFalse(SocialEnergy.seeded(seed = 42L).isEmotion)
    }

    @Test
    fun `acceptance - seed 와 입력이 같으면 같은 궤적을 재현한다 (replay 결정성)`() {
        fun run(): SocialEnergy =
            SocialEnergy
                .seeded(seed = 7L)
                .nudged(0.3, t0)
                .decayed(t0.plus(Duration.ofHours(3)))
                .nudged(-0.1, t0.plus(Duration.ofHours(3)))
        assertEquals(run(), run())
    }

    @Test
    fun `시간 감쇠 - baseline 으로 회귀한다 (영구 상태 금지)`() {
        val high = SocialEnergy.seeded(seed = 1L).nudged(0.5, t0) // level 1.0
        assertEquals(1.0, high.level)
        val later = high.decayed(t0.plus(Duration.ofHours(6))) // half-life 6h
        assertTrue(later.level < high.level, "감쇠로 baseline 쪽으로 내려간다")
        assertTrue(later.level > high.baseline, "한 번의 half-life 로는 baseline 까지 가지 않는다")
    }

    @Test
    fun `nudged 는 0~1 로 clamp 된다`() {
        assertEquals(1.0, SocialEnergy.seeded(1L).nudged(5.0, t0).level)
        assertEquals(0.0, SocialEnergy.seeded(1L).nudged(-5.0, t0).level)
    }
}
