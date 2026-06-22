package com.discordassistant.central.socialmemory.domain.service.relationship

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P06-T007: 성격으로 명명 안 함 + 낮은 표본에서 정책 약하게(confidence 게이트). */
class ObservedBanterAcceptanceTest {
    @Test
    fun `초기 - 표본 0 이면 rate 는 중립 절반, confidence 는 0`() {
        val e = ObservedBanterAcceptance.EMPTY
        assertEquals(0.5, e.acceptanceRate)
        assertEquals(0.0, e.acceptanceConfidence)
        assertEquals(0.0, e.weightedSignedAcceptance)
    }

    @Test
    fun `긍정 중단 신호를 관찰값으로 누적한다`() {
        val o =
            ObservedBanterAcceptance.EMPTY
                .observePositive()
                .observePositive()
                .observeStop()
        assertEquals(3, o.sampleCount)
        assertTrue(o.acceptanceRate > 0.5)
    }

    @Test
    fun `acceptance - 낮은 표본은 confidence 가 낮아 정책 가중치가 작다`() {
        val small = ObservedBanterAcceptance(positiveSignals = 2, stopSignals = 0)
        val large = ObservedBanterAcceptance(positiveSignals = 40, stopSignals = 0)
        assertTrue(small.acceptanceConfidence < large.acceptanceConfidence)
        // 둘 다 rate 1.0 이지만 저표본은 가중 부호값이 작다.
        assertTrue(small.weightedSignedAcceptance < large.weightedSignedAcceptance)
    }

    @Test
    fun `윤리 - 성격 라벨 필드 없이 positive stop 카운트만 갖는다`() {
        // 이 객체의 멤버는 positiveSignals/stopSignals(Int) 뿐이다 — 유머감각/MBTI 등 성격 라벨이 없다.
        val o = ObservedBanterAcceptance(positiveSignals = 5, stopSignals = 1)
        assertEquals(6, o.sampleCount)
    }
}
