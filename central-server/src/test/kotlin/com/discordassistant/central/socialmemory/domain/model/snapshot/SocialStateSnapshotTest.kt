package com.discordassistant.central.socialmemory.domain.model.snapshot

import com.discordassistant.central.socialmemory.domain.event.HumanOutcomeObserved
import com.discordassistant.central.socialmemory.domain.event.NexaActionKind
import com.discordassistant.central.socialmemory.domain.event.NexaActionObserved
import com.discordassistant.central.socialmemory.domain.event.SocialStateUpdate
import com.discordassistant.central.socialmemory.domain.model.relationship.InteractionOutcome
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P06-T018/T019 사회 상태 snapshot reducer·canonical hash 테스트.
 *
 * acceptance(T018): 원본 content 없이 source watermark 보존 — snapshot 은 카운트·watermark 만 담는다.
 * acceptance(T019): 같은 update 집합을 재구축하면 같은 hash — 도착 순서·중복과 무관하게 결정론적 snapshot/hash.
 */
class SocialStateSnapshotTest {
    private val key = MemberKey("g1", "m1")
    private val otherKey = MemberKey("g1", "m2")
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun action(
        seq: Int,
        kind: NexaActionKind,
    ): NexaActionObserved =
        NexaActionObserved(key, kind, projectionVersion = 1, sourceEventIds = listOf("a$seq"), observedAt = t0.plusSeconds(seq.toLong()))

    private fun outcome(
        seq: Int,
        o: InteractionOutcome,
    ): HumanOutcomeObserved =
        HumanOutcomeObserved(key, o, projectionVersion = 1, sourceEventIds = listOf("o$seq"), observedAt = t0.plusSeconds(seq.toLong()))

    private fun stream(): List<SocialStateUpdate> =
        listOf(
            action(1, NexaActionKind.ADDRESSED_MEMBER),
            action(2, NexaActionKind.RESPONDED_TO_MEMBER),
            outcome(3, InteractionOutcome.CONTINUED),
            outcome(4, InteractionOutcome.REACTED),
            outcome(5, InteractionOutcome.CONTINUED),
        )

    @Test
    fun `update 를 접어 교환 카운트와 결과 카운트와 watermark 를 반영한다`() {
        val s = SocialStateSnapshot.rebuild(key, 1, stream())
        assertEquals(1, s.interaction.nexaToMemberBursts)
        assertEquals(1, s.interaction.memberToNexaBursts)
        assertEquals(2, s.outcomeCounts[InteractionOutcome.CONTINUED])
        assertEquals(1, s.outcomeCounts[InteractionOutcome.REACTED])
        assertEquals("o5", s.lastSourceEventId, "마지막 적용 이벤트 watermark")
        assertEquals(t0.plusSeconds(5), s.lastObservedAt)
    }

    @Test
    fun `같은 stream 을 재구축하면 같은 hash (acceptance T019)`() {
        val first = SocialStateSnapshot.rebuild(key, 1, stream()).canonicalHash()
        val second = SocialStateSnapshot.rebuild(key, 1, stream()).canonicalHash()
        assertEquals(first, second)
    }

    @Test
    fun `역순·중복 도착도 canonical 화로 같은 hash`() {
        val base = SocialStateSnapshot.rebuild(key, 1, stream()).canonicalHash()
        val reversed = SocialStateSnapshot.rebuild(key, 1, stream().reversed()).canonicalHash()
        val withDup = SocialStateSnapshot.rebuild(key, 1, stream() + stream()).canonicalHash()
        assertEquals(base, reversed, "역순 도착도 같은 hash")
        assertEquals(base, withDup, "중복 idempotencyKey 는 멱등 — 같은 hash")
    }

    @Test
    fun `다른 key 의 update 는 무시된다`() {
        val foreign = NexaActionObserved(otherKey, NexaActionKind.ADDRESSED_MEMBER, 1, listOf("x1"), t0)
        val withForeign = SocialStateSnapshot.rebuild(key, 1, stream() + foreign).canonicalHash()
        assertEquals(SocialStateSnapshot.rebuild(key, 1, stream()).canonicalHash(), withForeign)
    }

    @Test
    fun `내용이 다르면 hash 가 다르다 (hash 가 상태를 실제로 반영)`() {
        val base = SocialStateSnapshot.rebuild(key, 1, stream()).canonicalHash()
        val more = SocialStateSnapshot.rebuild(key, 1, stream() + action(9, NexaActionKind.ADDRESSED_MEMBER)).canonicalHash()
        assertNotEquals(base, more)
    }

    @Test
    fun `다른 projectionVersion 의 update 는 걸러진다`() {
        val v2 =
            NexaActionObserved(key, NexaActionKind.ADDRESSED_MEMBER, projectionVersion = 2, sourceEventIds = listOf("v2"), observedAt = t0)
        val rebuilt = SocialStateSnapshot.rebuild(key, 1, stream() + v2)
        assertEquals(1, rebuilt.interaction.nexaToMemberBursts, "version 2 update 는 version 1 재구축에 포함되지 않는다")
    }

    @Test
    fun `빈 snapshot 은 원문을 담지 않고 watermark 가 null 이다 (acceptance T018)`() {
        val empty = SocialStateSnapshot.empty(key, 1)
        assertEquals(null, empty.lastSourceEventId)
        assertEquals(null, empty.lastObservedAt)
        assertEquals(emptyMap<InteractionOutcome, Int>(), empty.outcomeCounts)
    }
}
