package com.discordassistant.central.socialmemory.domain.service.fact

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P07-T009: 상충 object 를 CONFLICTED 로, 근거 부족 시 임의 승격 안 함. */
class FactConflictDetectionTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")
    private val source =
        MemorySource(sourceEventIds = setOf("e1"), extractionVersion = 1, consentGranted = true, createdAt = t0)

    private fun fact(
        id: String,
        obj: String,
        confidence: Double,
    ) = TemporalFact(
        id = id,
        visibility = VisibilityScope.Guild("g#1"),
        subject = "m#1",
        predicate = "uses_language",
        obj = obj,
        validFrom = t0,
        validTo = null,
        source = source,
        confidence = Confidence(confidence),
    )

    @Test
    fun `acceptance - 근거 부족(신뢰 비등)이면 임의로 한쪽을 승격하지 않고 모두 CONFLICTED`() {
        val a = fact("f1", "kotlin", 0.6)
        val b = fact("f2", "java", 0.55)
        val result = FactConflictDetection.resolve(listOf(a, b))
        assertTrue(result.conflicted)
        assertTrue(result.facts.all { it.status == MemoryStatus.CONFLICTED })
    }

    @Test
    fun `명확히 우세하면 그쪽만 현재 사실로 정리한다`() {
        val a = fact("f1", "kotlin", 0.9)
        val b = fact("f2", "java", 0.5)
        val result = FactConflictDetection.resolve(listOf(a, b))
        assertEquals(false, result.conflicted)
        assertEquals(MemoryStatus.ACTIVE, result.facts.first { it.id == "f1" }.status)
        assertEquals(MemoryStatus.SUPERSEDED, result.facts.first { it.id == "f2" }.status)
    }

    @Test
    fun `같은 object 재진술은 모순이 아니다`() {
        val a = fact("f1", "kotlin", 0.6)
        val b = fact("f2", "kotlin", 0.7)
        val result = FactConflictDetection.resolve(listOf(a, b))
        assertEquals(false, result.conflicted)
    }
}
