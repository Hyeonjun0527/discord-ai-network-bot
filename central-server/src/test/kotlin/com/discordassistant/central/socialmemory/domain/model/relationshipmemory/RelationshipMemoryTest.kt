package com.discordassistant.central.socialmemory.domain.model.relationshipmemory

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryEvidence
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P07-T005: 상호작용 사건+관찰 결과를 관계 상태와 분리, 성격 단정 대신 사건 기반 신호만. */
class RelationshipMemoryTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")
    private val source =
        MemorySource(sourceEventIds = setOf("e1"), extractionVersion = 1, consentGranted = true, createdAt = t0)

    @Test
    fun `acceptance - 관찰된 사건 신호(닫힌 집합)만 담는다 (성격 단정 불가)`() {
        val m =
            RelationshipMemory(
                id = "r1",
                visibility = VisibilityScope.Guild("g#1"),
                counterpartPseudonym = "m#1",
                occurredAt = t0,
                observedSignals = setOf(ObservedRelationshipSignal.REPLIED, ObservedRelationshipSignal.BANTER_RETURNED),
                source = source,
                confidence = Confidence.forEvidence(MemoryEvidence.EXPLICIT_DISCORD_EVENT),
                expiresAt = null,
            )
        // 신호는 닫힌 enum 이라 자유 텍스트 성격 라벨이 들어갈 자리가 없다.
        assertTrue(m.observedSignals.all { it is ObservedRelationshipSignal })
    }

    @Test
    fun `관찰 신호가 비면 거부한다 (사건 기반 기록 필수)`() {
        assertThrows(IllegalArgumentException::class.java) {
            RelationshipMemory(
                id = "r2",
                visibility = VisibilityScope.Guild("g#1"),
                counterpartPseudonym = "m#1",
                occurredAt = t0,
                observedSignals = emptySet(),
                source = source,
                confidence = Confidence.forEvidence(MemoryEvidence.EXPLICIT_DISCORD_EVENT),
                expiresAt = null,
            )
        }
    }
}
