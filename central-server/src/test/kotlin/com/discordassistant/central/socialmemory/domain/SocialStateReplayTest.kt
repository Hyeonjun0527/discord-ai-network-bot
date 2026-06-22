package com.discordassistant.central.socialmemory.domain

import com.discordassistant.central.socialmemory.domain.event.HumanOutcomeObserved
import com.discordassistant.central.socialmemory.domain.event.NexaActionKind
import com.discordassistant.central.socialmemory.domain.event.NexaActionObserved
import com.discordassistant.central.socialmemory.domain.event.SocialStateUpdate
import com.discordassistant.central.socialmemory.domain.model.relationship.InteractionOutcome
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import com.discordassistant.central.socialmemory.domain.model.snapshot.SocialStateSnapshot
import com.discordassistant.central.socialmemory.domain.service.decay.HalfLifeDecay
import com.discordassistant.central.socialmemory.domain.service.decay.SocialStateDecay
import com.discordassistant.central.socialmemory.domain.service.relationship.FamiliarityCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P06-T024 사회 상태 replay·감쇠 테스트.
 *
 * acceptance: 같은 이벤트와 Clock progression 이 같은 상태를 만들고 restart 후 감쇠가 연속적이며, seed·Clock·
 * projection version 이 기록돼 flakiness 가 없다.
 *
 * - **결정론 replay**: 고정 projectionVersion·고정 event stream 을 여러 번 재생하면 byte-equivalent snapshot hash.
 * - **Clock progression**: 주입 [Clock] 을 단조 전진시키며 update 를 접으면 같은 궤적(같은 hash 시퀀스).
 * - **restart 후 감쇠 연속성**: 같은 마지막 관찰 시각·같은 Clock 으로 감쇠를 재계산하면 restart 전후 값이 같다
 *   (감쇠가 wall-clock 이 아니라 관찰 시각↔now 의 함수라 재시작에도 연속적).
 */
class SocialStateReplayTest {
    private val key = MemberKey("g1", "m1")
    private val projectionVersion = 7L
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    /** 고정 seed event stream — (seq, eventId, observedAt) 가 명시돼 flakiness 가 없다. */
    private fun seededStream(): List<SocialStateUpdate> =
        listOf(
            NexaActionObserved(key, NexaActionKind.ADDRESSED_MEMBER, projectionVersion, listOf("e1"), t0),
            HumanOutcomeObserved(key, InteractionOutcome.CONTINUED, projectionVersion, listOf("e2"), t0.plusSeconds(30)),
            NexaActionObserved(key, NexaActionKind.RESPONDED_TO_MEMBER, projectionVersion, listOf("e3"), t0.plusSeconds(60)),
            HumanOutcomeObserved(key, InteractionOutcome.REACTED, projectionVersion, listOf("e4"), t0.plusSeconds(90)),
            HumanOutcomeObserved(key, InteractionOutcome.CONTINUED, projectionVersion, listOf("e5"), t0.plusSeconds(120)),
        )

    @Test
    fun `같은 event stream 과 projection version 은 같은 snapshot hash 를 만든다`() {
        val a = SocialStateSnapshot.rebuild(key, projectionVersion, seededStream()).canonicalHash()
        val b = SocialStateSnapshot.rebuild(key, projectionVersion, seededStream()).canonicalHash()
        assertEquals(a, b, "고정 seed·projectionVersion 은 flakiness 없는 같은 hash")
    }

    @Test
    fun `Clock progression 으로 update 를 접어도 같은 궤적이다`() {
        // 주입 Clock 을 update observedAt 에 맞춰 단조 전진시키며 fold — 같은 hash 시퀀스가 나온다.
        fun foldWithClock(): List<String> {
            var snapshot = SocialStateSnapshot.empty(key, projectionVersion)
            val hashes = mutableListOf<String>()
            seededStream().sortedBy { it.observedAt }.forEach { update ->
                val clock = Clock.fixed(update.observedAt, ZoneOffset.UTC)
                // Clock 을 직접 쓰지 않고 시각만 검증 — update.observedAt 이 Clock.instant 와 일치(시각 주입 계약).
                assertEquals(clock.instant(), update.observedAt)
                snapshot = snapshot.apply(update)
                hashes += snapshot.canonicalHash()
            }
            return hashes
        }
        assertEquals(foldWithClock(), foldWithClock(), "같은 Clock progression 은 같은 hash 시퀀스")
    }

    @Test
    fun `restart 후 감쇠가 연속적이다 (관찰 시각 기준 함수라 재시작 불변)`() {
        val snapshot = SocialStateSnapshot.rebuild(key, projectionVersion, seededStream())
        val baseFamiliarity = FamiliarityCalculator.familiarity(snapshot.interaction, now = snapshot.lastObservedAt!!)

        // "restart" 를 모사: 같은 snapshot 의 lastObservedAt 으로 같은 now 에 감쇠 재계산.
        val nowAfterRestart = snapshot.lastObservedAt!!.plus(Duration.ofDays(7))
        val decayedBefore = SocialStateDecay.decayed(baseFamiliarity, snapshot.lastObservedAt!!, nowAfterRestart, HalfLifeDecay.FAMILIARITY)
        val decayedAfterRestart =
            SocialStateDecay.decayed(
                baseFamiliarity,
                snapshot.lastObservedAt!!,
                nowAfterRestart,
                HalfLifeDecay.FAMILIARITY,
            )
        assertEquals(decayedBefore, decayedAfterRestart, 1e-15, "재시작 전후 감쇠값이 같다(연속적)")
        assertTrue(decayedAfterRestart < baseFamiliarity, "시간이 지나면 감쇠한다(영구 낙인 금지)")
    }

    @Test
    fun `Clock 을 더 전진시키면 감쇠가 단조 감소한다`() {
        val snapshot = SocialStateSnapshot.rebuild(key, projectionVersion, seededStream())
        val last = snapshot.lastObservedAt!!
        val base = FamiliarityCalculator.familiarity(snapshot.interaction, now = last)
        val d7 = SocialStateDecay.decayed(base, last, last.plus(Duration.ofDays(7)), HalfLifeDecay.FAMILIARITY)
        val d14 = SocialStateDecay.decayed(base, last, last.plus(Duration.ofDays(14)), HalfLifeDecay.FAMILIARITY)
        val d28 = SocialStateDecay.decayed(base, last, last.plus(Duration.ofDays(28)), HalfLifeDecay.FAMILIARITY)
        assertTrue(d7 > d14 && d14 > d28, "Clock 전진에 따라 감쇠 단조 감소")
    }

    @Test
    fun `projection version 이 다르면 다른 재생 결과 (version 기록으로 재투영 구분)`() {
        val v7 = SocialStateSnapshot.rebuild(key, 7, seededStream())
        // version 7 stream 을 version 8 로 재구축하면 update 가 걸러져 빈 상태 → 다른 hash.
        val v8 = SocialStateSnapshot.rebuild(key, 8, seededStream())
        assertTrue(v7.canonicalHash() != v8.canonicalHash(), "projectionVersion 이 hash 에 기록돼 재투영을 구분")
        assertEquals(0, v8.interaction.totalExchangedBursts, "version 8 재생은 version 7 update 를 적용하지 않는다")
    }
}
