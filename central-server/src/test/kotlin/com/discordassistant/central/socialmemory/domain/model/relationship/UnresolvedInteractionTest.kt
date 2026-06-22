package com.discordassistant.central.socialmemory.domain.model.relationship

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** NEXA-P06-T015: expiry 보존 + 만료/해결 이벤트(영구 누적 금지). */
class UnresolvedInteractionTest {
    private val key = MemberKey(guildPseudonym = "g#1", memberPseudonym = "m#1")
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    private fun open() =
        UnresolvedInteraction.open(
            key = key,
            kind = UnresolvedKind.MEMBER_QUESTION_OPEN,
            sourceEventId = "evt-1",
            openedAt = t0,
            ttl = Duration.ofHours(1),
        )

    @Test
    fun `acceptance - ttl 이 양수가 아니면 미해결을 만들 수 없다 (영구 미해결 금지)`() {
        assertThrows(IllegalArgumentException::class.java) {
            UnresolvedInteraction.open(key, UnresolvedKind.NEXA_CUT_OFF, "e", t0, Duration.ZERO)
        }
    }

    @Test
    fun `만료 시각 전에는 활성, 후에는 만료`() {
        val u = open()
        assertTrue(u.isActive(t0.plus(Duration.ofMinutes(30))))
        assertFalse(u.isActive(t0.plus(Duration.ofHours(2))))
        assertTrue(u.isExpired(t0.plus(Duration.ofHours(2))))
    }

    @Test
    fun `acceptance - 만료 이벤트로 닫힌다`() {
        val expired = open().expireIfDue(t0.plus(Duration.ofHours(2)))
        assertEquals(UnresolvedStatus.EXPIRED, expired.status)
        assertEquals(t0.plus(Duration.ofHours(1)), expired.closedAt)
        assertFalse(expired.isActive(t0.plus(Duration.ofHours(2))))
    }

    @Test
    fun `acceptance - 해결 이벤트로 닫힌다`() {
        val resolved = open().resolve(t0.plus(Duration.ofMinutes(10)))
        assertEquals(UnresolvedStatus.RESOLVED, resolved.status)
        assertEquals(t0.plus(Duration.ofMinutes(10)), resolved.closedAt)
    }

    @Test
    fun `이미 해결된 건은 만료로 덮어쓰지 않는다`() {
        val resolved = open().resolve(t0.plusSeconds(60))
        assertEquals(resolved, resolved.expireIfDue(t0.plus(Duration.ofHours(2))))
    }
}
