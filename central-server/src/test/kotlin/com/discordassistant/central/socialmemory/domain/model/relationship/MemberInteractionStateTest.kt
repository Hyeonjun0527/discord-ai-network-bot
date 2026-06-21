package com.discordassistant.central.socialmemory.domain.model.relationship

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P06-T004: guild-scoped key(전역 프로필 아님) + 관찰 통계 집계. */
class MemberInteractionStateTest {
    private val key = MemberKey(guildPseudonym = "g#1", memberPseudonym = "m#1")
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    @Test
    fun `acceptance - 같은 사용자라도 guild 가 다르면 다른 키 (cross-guild 연결 금지)`() {
        val g1 = MemberKey(guildPseudonym = "g#1", memberPseudonym = "m#1")
        val g2 = MemberKey(guildPseudonym = "g#2", memberPseudonym = "m#1")
        assertNotEquals(g1, g2)
    }

    @Test
    fun `초기 상태는 상호작용 없음`() {
        val s = MemberInteractionState.empty(key)
        assertFalse(s.hasInteracted)
        assertEquals(0, s.totalExchangedBursts)
    }

    @Test
    fun `양방향 burst 를 관찰값으로 집계한다`() {
        val s =
            MemberInteractionState
                .empty(key)
                .recordNexaToMember(t0)
                .recordMemberToNexa(t0.plusSeconds(5))
        assertEquals(1, s.nexaToMemberBursts)
        assertEquals(1, s.memberToNexaBursts)
        assertEquals(2, s.totalExchangedBursts)
        assertEquals(t0.plusSeconds(5), s.lastInteractionAt)
        assertTrue(s.hasInteracted)
    }

    @Test
    fun `lastInteractionAt 은 가장 최근 시각으로만 전진한다`() {
        val s = MemberInteractionState.empty(key).recordNexaToMember(t0.plusSeconds(100)).recordMemberToNexa(t0)
        assertEquals(t0.plusSeconds(100), s.lastInteractionAt)
    }
}
