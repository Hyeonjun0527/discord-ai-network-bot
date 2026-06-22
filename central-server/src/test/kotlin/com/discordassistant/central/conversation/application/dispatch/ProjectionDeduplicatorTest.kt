package com.discordassistant.central.conversation.application.dispatch

import com.discordassistant.central.conversation.domain.model.event.EventId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P03-T016 acceptance: 동일 fixture 를 10 회 재생해도 projection input 수가 한 번과 같다.
 */
class ProjectionDeduplicatorTest {
    @Test
    fun `같은 fixture 를 10회 재생해도 projection input 은 한 번뿐이다`() {
        val ledger = InMemoryProjectionLedger()
        val dedup = ProjectionDeduplicator(ledger, projectionVersion = 1)
        val fixture = listOf(EventId("a"), EventId("b"), EventId("c"))

        var projectionInputs = 0
        repeat(10) {
            for (eventId in fixture) {
                if (dedup.shouldApply(eventId)) projectionInputs += 1
            }
        }

        // 10 회 재생했지만 projection 으로 흘러간 건 3 개(첫 1 회)뿐.
        assertEquals(fixture.size, projectionInputs, "재생 횟수와 무관하게 projection input 은 1 회분")
    }

    @Test
    fun `shouldApply 는 처음만 true 이후 false 다`() {
        val dedup = ProjectionDeduplicator(InMemoryProjectionLedger(), projectionVersion = 1)
        assertTrue(dedup.shouldApply(EventId("x")))
        assertFalse(dedup.shouldApply(EventId("x")))
        assertTrue(dedup.isDuplicate(EventId("x")))
    }

    @Test
    fun `다른 projection version 으로는 다시 적용된다`() {
        val ledger = InMemoryProjectionLedger()
        assertTrue(ProjectionDeduplicator(ledger, 1).shouldApply(EventId("x")))
        // version 2 는 별개 — 재투영 목적.
        assertTrue(ProjectionDeduplicator(ledger, 2).shouldApply(EventId("x")))
    }
}
