package com.discordassistant.central.socialmemory.application.privacy

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P07-T013: source event redaction → INVALIDATED 또는 삭제. 부분 출처 잔존 시 confidence 재계산. */
class CascadeMemoryRedactionServiceTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")
    private val service = CascadeMemoryRedactionService()

    private fun fact(
        id: String,
        eventIds: Set<String>,
        confidence: Double,
    ) = TemporalFact(
        id = id,
        visibility = VisibilityScope.Guild("g#1"),
        subject = "m#1",
        predicate = "uses_language",
        obj = "kotlin",
        validFrom = t0,
        validTo = null,
        source = MemorySource(sourceEventIds = eventIds, extractionVersion = 1, consentGranted = true, createdAt = t0),
        confidence = Confidence(confidence),
    )

    @Test
    fun `acceptance - 부분 출처가 남은 기억의 confidence 가 남은 비율로 재계산된다`() {
        // 출처 4개 중 1개 삭제 → 남은 비율 3/4 → confidence 0.8 * 0.75 = 0.6.
        val f = fact("f1", setOf("e1", "e2", "e3", "e4"), 0.8)
        val result = service.cascadeFacts(listOf(f), redactedEventIds = setOf("e1"))

        val updated = result.memories.single()
        assertEquals(setOf("e2", "e3", "e4"), updated.source.sourceEventIds)
        assertEquals(0.6, updated.confidence.value, 1e-9)
        assertEquals(MemoryStatus.ACTIVE, updated.status)
        assertEquals(1, result.weakenedCount)
        assertEquals(0, result.invalidatedCount)
    }

    @Test
    fun `출처가 모두 삭제되면 INVALIDATED 된다`() {
        val f = fact("f1", setOf("e1", "e2"), 0.8)
        val result = service.cascadeFacts(listOf(f), redactedEventIds = setOf("e1", "e2"))
        assertEquals(MemoryStatus.INVALIDATED, result.memories.single().status)
        assertEquals(1, result.invalidatedCount)
    }

    @Test
    fun `무관한 redaction 은 기억을 바꾸지 않는다`() {
        val f = fact("f1", setOf("e1", "e2"), 0.8)
        val result = service.cascadeFacts(listOf(f), redactedEventIds = setOf("eX"))
        val updated = result.memories.single()
        assertEquals(0.8, updated.confidence.value, 1e-9)
        assertEquals(MemoryStatus.ACTIVE, updated.status)
        assertTrue(result.weakenedCount == 0 && result.invalidatedCount == 0)
    }
}
