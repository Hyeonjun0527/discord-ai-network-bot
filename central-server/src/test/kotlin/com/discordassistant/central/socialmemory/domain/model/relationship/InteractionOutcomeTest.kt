package com.discordassistant.central.socialmemory.domain.model.relationship

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P06-T010: 닫힌 결과 코드 + source event IDs, 자유 텍스트 심리 판정 없음. */
class InteractionOutcomeTest {
    private val key = MemberKey(guildPseudonym = "g#1", memberPseudonym = "m#1")
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    @Test
    fun `결과 코드는 닫힌 집합이며 wireName 으로 왕복한다`() {
        InteractionOutcome.entries.forEach {
            assertEquals(it, InteractionOutcome.fromWireName(it.wireName))
        }
        assertEquals(null, InteractionOutcome.fromWireName("화남")) // 심리 라벨은 없다.
    }

    @Test
    fun `acceptance - source event ID 없이는 결과를 만들 수 없다 (provenance)`() {
        assertThrows(IllegalArgumentException::class.java) {
            ObservedInteractionOutcome(key, InteractionOutcome.IGNORED, emptyList(), t0)
        }
    }

    @Test
    fun `acceptance - 자유 텍스트 판정 없이 코드와 source event 만 담는다`() {
        val o = ObservedInteractionOutcome(key, InteractionOutcome.REACTED, listOf("evt-1", "evt-2"), t0)
        assertFalse(o.freeTextJudgmentPresent)
        assertEquals(InteractionOutcome.REACTED, o.outcome)
        assertEquals(listOf("evt-1", "evt-2"), o.sourceEventIds)
    }
}
