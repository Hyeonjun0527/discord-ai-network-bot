package com.discordassistant.central.socialmemory.domain.service.retention

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryEvidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.episodic.EpisodicMemory
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** NEXA-P07-T012: 유형별 TTL·만료 event(Clock 기반). 만료 기억은 retrieval 에서 제외된다. */
class MemoryExpirySweepTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")
    private val source =
        MemorySource(sourceEventIds = setOf("e1"), extractionVersion = 1, consentGranted = true, createdAt = t0)

    private fun episodic(expiresAt: Instant?) =
        EpisodicMemory(
            id = "ep1",
            visibility = VisibilityScope.Guild("g#1"),
            summary = "사건",
            participants = setOf("m#1"),
            occurredAt = t0,
            source = source,
            confidence = Confidence.forEvidence(MemoryEvidence.EXPLICIT_DISCORD_EVENT),
            expiresAt = expiresAt,
        )

    private fun sweepAt(now: Instant) = MemoryExpirySweep(Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `acceptance - 만료된 기억은 EXPIRED 로 전이되고 retrieval 에서 빠진다 + 만료 event 발생`() {
        val expiresAt = t0.plusSeconds(100)
        val outcome = sweepAt(expiresAt).sweepEpisodic(episodic(expiresAt))

        assertEquals(MemoryStatus.EXPIRED, outcome.memory.status)
        assertFalse(outcome.memory.isRetrievableAt(expiresAt))
        assertNotNull(outcome.event)
        assertEquals(MemoryRetentionPolicy.EPISODIC, outcome.event?.kind)
        assertEquals("ep1", outcome.event?.memoryId)
    }

    @Test
    fun `만료 전이면 변화 없음(event 없음)`() {
        val outcome = sweepAt(t0).sweepEpisodic(episodic(t0.plusSeconds(100)))
        assertEquals(MemoryStatus.ACTIVE, outcome.memory.status)
        assertNull(outcome.event)
    }

    @Test
    fun `TTL 정책은 유형별로 만료 시각을 계산한다`() {
        val intentExpiry = MemoryRetentionPolicy.PENDING_INTENT.expiryFrom(t0)
        val factExpiry = MemoryRetentionPolicy.TEMPORAL_FACT.expiryFrom(t0)
        // 의도(단기) < 사실(장기).
        assertEquals(true, intentExpiry.isBefore(factExpiry))
    }
}
