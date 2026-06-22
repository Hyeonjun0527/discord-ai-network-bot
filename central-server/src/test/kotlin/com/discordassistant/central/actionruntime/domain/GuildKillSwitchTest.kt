package com.discordassistant.central.actionruntime.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P18-T013 — GuildKillSwitch 순수 결정 코어: kill 된 길드는 BLOCK, 그 외 ALLOW(즉시 발효는 활성 집합 조회).
 */
class GuildKillSwitchTest {
    @Test
    fun `killed guild is blocked`() {
        val decision = GuildKillSwitch.decide("g-1", setOf("g-1", "g-9"))
        assertEquals(KillSwitchDecision.BLOCK, decision)
        assertTrue(decision.isBlocked)
    }

    @Test
    fun `non-killed guild is allowed`() {
        val decision = GuildKillSwitch.decide("g-2", setOf("g-1"))
        assertEquals(KillSwitchDecision.ALLOW, decision)
        assertFalse(decision.isBlocked)
    }

    @Test
    fun `empty active set allows all`() {
        assertEquals(KillSwitchDecision.ALLOW, GuildKillSwitch.decide("g-1", emptySet()))
    }
}
