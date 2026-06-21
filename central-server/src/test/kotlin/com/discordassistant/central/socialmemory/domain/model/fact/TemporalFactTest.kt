package com.discordassistant.central.socialmemory.domain.model.fact

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryEvidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P07-T004: subject/predicate/object·validFrom/validTo·confidence·status. 현재 사실 ∥ 과거 사실 공존. */
class TemporalFactTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")
    private val source =
        MemorySource(sourceEventIds = setOf("e1"), extractionVersion = 1, consentGranted = true, createdAt = t0)

    private fun fact(
        obj: String,
        validTo: Instant?,
        status: MemoryStatus = MemoryStatus.ACTIVE,
    ) = TemporalFact(
        id = "f-$obj",
        visibility = VisibilityScope.Guild("g#1"),
        subject = "m#1",
        predicate = "uses_language",
        obj = obj,
        validFrom = t0,
        validTo = validTo,
        source = source,
        confidence = Confidence.forEvidence(MemoryEvidence.EXPLICIT_DISCORD_EVENT),
        status = status,
    )

    @Test
    fun `acceptance - 현재 사실(validTo null)과 종료된 과거 사실을 동시에 보존한다`() {
        val past = fact("java", validTo = t0.plusSeconds(100), status = MemoryStatus.SUPERSEDED)
        val current = fact("kotlin", validTo = null)
        // 둘 다 유효한 도메인 객체로 공존한다 — 과거는 닫혔고 현재는 열림.
        assertFalse(past.isCurrent)
        assertTrue(current.isCurrent)
        assertTrue(past.sameClaimAs(current))
    }

    @Test
    fun `validTo 는 validFrom 이전일 수 없다`() {
        assertThrows(IllegalArgumentException::class.java) {
            fact("x", validTo = t0.minusSeconds(1))
        }
    }

    @Test
    fun `유효 구간과 retrieval 판정`() {
        val current = fact("kotlin", validTo = null)
        assertTrue(current.isRetrievableAt(t0.plusSeconds(50)))
        val closed = fact("java", validTo = t0.plusSeconds(100), status = MemoryStatus.SUPERSEDED)
        // SUPERSEDED 는 현재 조회 제외.
        assertFalse(closed.isRetrievableAt(t0.plusSeconds(50)))
    }
}
