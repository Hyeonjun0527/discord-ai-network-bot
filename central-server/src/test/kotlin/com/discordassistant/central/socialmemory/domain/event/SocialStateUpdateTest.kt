package com.discordassistant.central.socialmemory.domain.event

import com.discordassistant.central.participation.domain.model.state.ChannelScope
import com.discordassistant.central.socialmemory.domain.model.relationship.InteractionOutcome
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P06-T016 사회 상태 update event 정의 테스트.
 *
 * acceptance: 각 update 가 idempotency key 와 projection version 을 가진다 — 세 변형 모두 비어 있지 않은
 * idempotencyKey 와 음수 아닌 projectionVersion·provenance(source event IDs)를 갖고, 같은 사실은 같은 키다.
 */
class SocialStateUpdateTest {
    private val scope = ChannelScope("g1", "c1")
    private val key = MemberKey("g1", "m1")
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `세 변형 모두 idempotency key 와 projection version 과 provenance 를 가진다`() {
        val updates: List<SocialStateUpdate> =
            listOf(
                SceneUpdateObserved(
                    scope,
                    sceneSeq = 3,
                    sceneContextVersion = 2,
                    projectionVersion = 1,
                    sourceEventIds = listOf("e1"),
                    observedAt = t0,
                ),
                NexaActionObserved(
                    key,
                    NexaActionKind.ADDRESSED_MEMBER,
                    projectionVersion = 1,
                    sourceEventIds = listOf("e2"),
                    observedAt = t0,
                ),
                HumanOutcomeObserved(
                    key,
                    InteractionOutcome.CONTINUED,
                    projectionVersion = 1,
                    sourceEventIds = listOf("e3"),
                    observedAt = t0,
                ),
            )
        updates.forEach {
            assert(it.idempotencyKey.isNotBlank()) { "idempotencyKey 가 비어 있다" }
            assert(it.projectionVersion >= 0)
            assert(it.sourceEventIds.isNotEmpty())
        }
    }

    @Test
    fun `같은 장면 순번은 같은 idempotency key, 다른 순번은 다른 key`() {
        val a = SceneUpdateObserved(scope, 3, 2, 1, listOf("e1"), t0)
        val b = SceneUpdateObserved(scope, 3, 2, 1, listOf("e1-dup"), t0)
        val c = SceneUpdateObserved(scope, 4, 2, 1, listOf("e1"), t0)
        assertEquals(a.idempotencyKey, b.idempotencyKey, "같은 (scope, sceneSeq) 는 같은 멱등키")
        assertNotEquals(a.idempotencyKey, c.idempotencyKey, "다른 sceneSeq 는 다른 멱등키")
    }

    @Test
    fun `provenance 가 비면 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            NexaActionObserved(key, NexaActionKind.ADDRESSED_MEMBER, 1, emptyList(), t0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HumanOutcomeObserved(key, InteractionOutcome.REACTED, 1, listOf(" "), t0)
        }
    }

    @Test
    fun `음수 projection version 은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneUpdateObserved(scope, 1, 0, -1, listOf("e1"), t0)
        }
    }
}
