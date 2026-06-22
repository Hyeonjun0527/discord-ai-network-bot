package com.discordassistant.central.socialmemory.domain.service.retention

import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.episodic.EpisodicMemory
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.intent.PendingIntent
import com.discordassistant.central.socialmemory.domain.model.relationshipmemory.RelationshipMemory
import java.time.Clock
import java.time.Instant

/**
 * TTL 경과 기억을 **만료 처리**하는 Clock 기반 스윕(NEXA-P07-T012, 순수 도메인 서비스).
 *
 * **acceptance(T012) — 만료된 기억이 retrieval 에 포함되지 않고 lineage 는 정책대로 처리된다**: 스윕은 만료된 ACTIVE 기억을
 * 물리 삭제하지 않고 status 를 [MemoryStatus.EXPIRED] 로 바꾼 새 값과 [MemoryExpired] 이벤트를 돌려준다. EXPIRED 는
 * [isRetrievable]=false 라 현재 조회에서 빠지고, 이벤트는 파생(임베딩 등) 만료 전파의 트리거가 된다(retention-policy 불변식 2).
 *
 * 만료 판정은 aggregate 자신의 시간 유효성(episodic/relationship/intent 는 expiresAt, fact 는 validTo)을 따른다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time(Clock) 만 쓴다.
 */
class MemoryExpirySweep(
    private val clock: Clock,
) {
    /** [memory] 가 만료됐으면 EXPIRED 새 값 + 이벤트, 아니면 변화 없음(null 이벤트). */
    fun sweepEpisodic(memory: EpisodicMemory): SweepOutcome<EpisodicMemory> {
        val now = Instant.now(clock)
        if (memory.status != MemoryStatus.ACTIVE || !memory.isExpiredAt(now)) {
            return SweepOutcome(memory, null)
        }
        return SweepOutcome(
            memory.copy(status = MemoryStatus.EXPIRED),
            MemoryExpired(memoryId = memory.id, kind = MemoryRetentionPolicy.EPISODIC, expiredAt = now),
        )
    }

    /** TemporalFact 는 validTo(없으면 미만료) 기준으로 만료한다 — supersession 으로 닫힌 건 건드리지 않는다. */
    fun sweepFact(fact: TemporalFact): SweepOutcome<TemporalFact> {
        val now = Instant.now(clock)
        val expired = fact.validTo != null && !now.isBefore(fact.validTo)
        if (fact.status != MemoryStatus.ACTIVE || !expired) {
            return SweepOutcome(fact, null)
        }
        return SweepOutcome(
            fact.copy(status = MemoryStatus.EXPIRED),
            MemoryExpired(memoryId = fact.id, kind = MemoryRetentionPolicy.TEMPORAL_FACT, expiredAt = now),
        )
    }

    /** RelationshipMemory 만료. */
    fun sweepRelationship(memory: RelationshipMemory): SweepOutcome<RelationshipMemory> {
        val now = Instant.now(clock)
        if (memory.status != MemoryStatus.ACTIVE || !memory.isExpiredAt(now)) {
            return SweepOutcome(memory, null)
        }
        return SweepOutcome(
            memory.copy(status = MemoryStatus.EXPIRED),
            MemoryExpired(memoryId = memory.id, kind = MemoryRetentionPolicy.RELATIONSHIP, expiredAt = now),
        )
    }

    /** PendingIntent 만료. */
    fun sweepIntent(intent: PendingIntent): SweepOutcome<PendingIntent> {
        val now = Instant.now(clock)
        if (intent.status != MemoryStatus.ACTIVE || !intent.isExpiredAt(now)) {
            return SweepOutcome(intent, null)
        }
        return SweepOutcome(
            intent.copy(status = MemoryStatus.EXPIRED),
            MemoryExpired(memoryId = intent.id, kind = MemoryRetentionPolicy.PENDING_INTENT, expiredAt = now),
        )
    }
}

/** 스윕 한 건 결과: [memory] 는 (만료됐다면) 새 상태, [event] 는 만료 이벤트(미만료면 null). */
data class SweepOutcome<T>(
    val memory: T,
    val event: MemoryExpired?,
)

/**
 * 기억 만료 도메인 이벤트(NEXA-P07-T012). 만료된 기억의 id·유형·시각만 담는다(원문 없음) — 파생(임베딩 등) 만료 전파의
 * 트리거(retention-policy 불변식 2, deletion-propagation 연계).
 */
data class MemoryExpired(
    val memoryId: String,
    val kind: MemoryRetentionPolicy,
    val expiredAt: Instant,
)
