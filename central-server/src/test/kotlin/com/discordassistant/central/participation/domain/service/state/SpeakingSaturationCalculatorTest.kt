package com.discordassistant.central.participation.domain.service.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P06-T011: burst 수(메시지 평균 아님) 기반 포화도. */
class SpeakingSaturationCalculatorTest {
    @Test
    fun `발화가 전혀 없으면 포화도 0`() {
        assertEquals(0.0, SpeakingSaturationCalculator.saturation(nexaBurstCount = 0, humanBurstCount = 0))
    }

    @Test
    fun `acceptance - NEXA 가 사람보다 많이 말하면 포화도가 높다 (burst 점유율)`() {
        val high = SpeakingSaturationCalculator.saturation(nexaBurstCount = 8, humanBurstCount = 2)
        val low = SpeakingSaturationCalculator.saturation(nexaBurstCount = 2, humanBurstCount = 8)
        assertTrue(high > low, "NEXA burst 점유율이 높으면 포화도가 높다")
    }

    @Test
    fun `조용한 채널에서 NEXA 1회는 과포화로 보지 않는다 (절대량 가중)`() {
        // 점유율은 1.0 이지만 절대 발화량(1)이 적어 saturation 이 1 보다 충분히 작다.
        val s = SpeakingSaturationCalculator.saturation(nexaBurstCount = 1, humanBurstCount = 0)
        assertTrue(s < 0.5, "발화 1회만으로 과포화가 아니다: $s")
    }

    @Test
    fun `포화도는 0~1 범위`() {
        val s = SpeakingSaturationCalculator.saturation(nexaBurstCount = 100, humanBurstCount = 0)
        assertTrue(s in 0.0..1.0)
    }
}
