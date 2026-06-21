package com.discordassistant.central.socialmemory.domain.service.relationship

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** NEXA-P06-T008: timing feature 일 뿐 응답 의무 아님. */
class ResponseExpectationTest {
    @Test
    fun `acceptance - isObligation 은 항상 false (응답 의무 아님)`() {
        assertFalse(ResponseExpectation.EMPTY.isObligation)
    }

    @Test
    fun `재호출 비율을 계산한다`() {
        val e = ResponseExpectation(typicalWait = Duration.ofSeconds(20), recalls = 2, directCalls = 4)
        assertEquals(0.5, e.recallRate)
        assertTrue(e.hasSample)
    }

    @Test
    fun `표본 없으면 recallRate 0`() {
        assertEquals(0.0, ResponseExpectation.EMPTY.recallRate)
        assertFalse(ResponseExpectation.EMPTY.hasSample)
    }
}
