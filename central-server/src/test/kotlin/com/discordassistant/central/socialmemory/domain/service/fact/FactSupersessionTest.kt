package com.discordassistant.central.socialmemory.domain.service.fact

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryEvidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P07-T008: 새 fact 가 이전 fact 를 대체할 때 validTo·supersedes edge 설정, 물리 삭제 안 함. */
class FactSupersessionTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")
    private val source =
        MemorySource(sourceEventIds = setOf("e1"), extractionVersion = 1, consentGranted = true, createdAt = t0)

    private fun fact(
        id: String,
        obj: String,
    ) = TemporalFact(
        id = id,
        visibility = VisibilityScope.Guild("g#1"),
        subject = "m#1",
        predicate = "uses_language",
        obj = obj,
        validFrom = t0,
        validTo = null,
        source = source,
        confidence = Confidence.forEvidence(MemoryEvidence.EXPLICIT_DISCORD_EVENT),
    )

    @Test
    fun `acceptance - 이전 fact 를 물리 삭제하지 않고 현재 조회에서 제외한다`() {
        val previous = fact("f1", "java")
        val next = fact("f2", "kotlin")
        val at = t0.plusSeconds(100)
        val result = FactSupersession.supersede(previous, next, at)

        // 이전 fact 는 보존되되 validTo 가 채워지고 SUPERSEDED 로 닫힌다.
        assertEquals(at, result.superseded.validTo)
        assertEquals(MemoryStatus.SUPERSEDED, result.superseded.status)
        // 현재 조회 제외.
        assertEquals(false, result.superseded.isRetrievableAt(t0.plusSeconds(150)))
        // 새 fact 는 현재.
        assertEquals(true, result.current.isCurrent)
        // lineage edge.
        assertEquals("f1", result.supersedesEdge.supersededFactId)
        assertEquals("f2", result.supersedesEdge.supersedingFactId)
    }

    @Test
    fun `다른 주장은 대체할 수 없다`() {
        val a = fact("f1", "java")
        val b = a.copy(id = "f2", predicate = "plays_game")
        assertThrows(IllegalArgumentException::class.java) { FactSupersession.supersede(a, b, t0.plusSeconds(1)) }
    }
}
