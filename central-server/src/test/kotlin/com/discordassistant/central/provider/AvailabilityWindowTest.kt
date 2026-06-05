package com.discordassistant.central.provider

import com.discordassistant.central.provider.domain.policy.AvailabilityWindow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 가용 시간대 판정(차수 12 #159) — 일반/자정넘김/항상가용 경계. */
class AvailabilityWindowTest {
    @Test
    fun `null 또는 from==to 는 항상 가용`() {
        assertTrue(AvailabilityWindow.isWithin(null, 6, 3))
        assertTrue(AvailabilityWindow.isWithin(9, null, 3))
        assertTrue(AvailabilityWindow.isWithin(8, 8, 23)) // 24시간
    }

    @Test
    fun `같은 날 구간 9~18`() {
        assertFalse(AvailabilityWindow.isWithin(9, 18, 8))
        assertTrue(AvailabilityWindow.isWithin(9, 18, 9)) // 시작 포함
        assertTrue(AvailabilityWindow.isWithin(9, 18, 17))
        assertFalse(AvailabilityWindow.isWithin(9, 18, 18)) // 종료 미포함
    }

    @Test
    fun `자정 넘김 구간 22~6`() {
        assertTrue(AvailabilityWindow.isWithin(22, 6, 23))
        assertTrue(AvailabilityWindow.isWithin(22, 6, 0))
        assertTrue(AvailabilityWindow.isWithin(22, 6, 5))
        assertFalse(AvailabilityWindow.isWithin(22, 6, 6)) // 종료 미포함
        assertFalse(AvailabilityWindow.isWithin(22, 6, 12))
    }
}
