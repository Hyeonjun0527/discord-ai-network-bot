package com.discordassistant.central.socialmemory.domain.model.episodic

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P07-T003: 시점 사건 요약·참여자 scope·source·expiry, 원문 복사 대신 구조화 요약+provenance. */
class EpisodicMemoryTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")
    private val source =
        MemorySource(sourceEventIds = setOf("e1"), extractionVersion = 1, consentGranted = true, createdAt = t0)

    private fun memory(expiresAt: Instant?) =
        EpisodicMemory(
            id = "ep1",
            visibility = VisibilityScope.Guild("g#1"),
            summary = "민수와 도커 문제를 함께 해결",
            participants = setOf("m#1", "m#2"),
            occurredAt = t0,
            source = source,
            confidence =
                Confidence.forEvidence(
                    com.discordassistant.central.socialmemory.domain.model.MemoryEvidence.EXPLICIT_DISCORD_EVENT,
                ),
            expiresAt = expiresAt,
        )

    @Test
    fun `acceptance - 구조화 요약과 provenance 를 보존한다 (원문 복사 아님)`() {
        val m = memory(null)
        assertTrue(m.summary.isNotBlank())
        assertTrue(m.source.sourceEventIds.contains("e1"))
        assertTrue(m.participants.containsAll(setOf("m#1", "m#2")))
    }

    @Test
    fun `빈 요약은 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            memory(null).copy(summary = " ")
        }
    }

    @Test
    fun `만료 시각 이후 retrieval 에서 빠진다`() {
        val m = memory(t0.plusSeconds(100))
        assertTrue(m.isRetrievableAt(t0))
        assertFalse(m.isRetrievableAt(t0.plusSeconds(100)))
    }
}
