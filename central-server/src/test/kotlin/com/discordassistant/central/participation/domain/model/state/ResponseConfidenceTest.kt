package com.discordassistant.central.participation.domain.model.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P06-T014: 세 신호 결합, 낮은 confidence != 침묵 강제(정책 입력일 뿐). */
class ResponseConfidenceTest {
    @Test
    fun `acceptance - dictatesSilence 는 항상 false (행동 결정 아님)`() {
        val low = ResponseConfidence(0.1, 0.1, 0.1)
        assertFalse(low.dictatesSilence) // 낮아도 침묵을 강제하지 않는다.
        assertTrue(low.confidence < 0.5)
    }

    @Test
    fun `세 신호의 가중 평균을 계산한다`() {
        val c = ResponseConfidence(addresseeConfidence = 1.0, factualityConfidence = 0.0, memoryConfidence = 0.5)
        assertEquals(0.5, c.confidence, 1e-9) // 동일 가중치 평균 (1+0+0.5)/3.
    }

    @Test
    fun `가중치를 바꾸면 결합값이 달라진다`() {
        val weighted =
            ResponseConfidence(
                addresseeConfidence = 1.0,
                factualityConfidence = 0.0,
                memoryConfidence = 0.0,
                weights = ConfidenceWeights(addressee = 3.0, factuality = 1.0, memory = 1.0),
            )
        assertEquals(0.6, weighted.confidence, 1e-9) // (3*1)/(3+1+1).
    }
}
