package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.application.port.out.DeadLetterRecord
import com.discordassistant.central.conversation.application.port.out.ProjectionLedgerPort
import com.discordassistant.central.conversation.domain.model.event.EventId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * [ProjectionLedgerPort] 의 JPA 구현 어댑터(NEXA-P03-T016/T018). projection 적용 dedup 기록과 dead-letter
 * 격리를 영속화한다(Flyway V52).
 *
 * **dedup(T016)**: [markApplied] 는 (event_id, projection_version) 유니크 사전 검사로 처음 적용만 true 를
 * 돌려준다 — 같은 fixture 를 N 번 재생해도 projection input 으로 통과하는 건 1 회뿐이다(멱등).
 *
 * **dead-letter(T018)**: 원문을 담지 않는다 — event_id·실패 분류 코드·짧은 진단·시각만 적재한다. 운영자는
 * event ID 로만 조사한다(logging-boundary.md). [reasonCode]/[detail] 은 호출자가 원문/PII 없이 채운다.
 */
@Repository
class JpaProjectionLedger(
    private val ledger: NexaProjectionLedgerRepository,
    private val deadLetters: NexaProjectionDeadLetterRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ProjectionLedgerPort {
    @Transactional
    override fun markApplied(
        eventId: EventId,
        projectionVersion: Int,
    ): Boolean {
        if (ledger.existsByEventIdAndProjectionVersion(eventId.value, projectionVersion)) {
            return false
        }
        ledger.save(
            NexaProjectionLedgerEntity(
                eventId = eventId.value,
                projectionVersion = projectionVersion,
                appliedAt = clock.instant(),
            ),
        )
        return true
    }

    @Transactional(readOnly = true)
    override fun isApplied(
        eventId: EventId,
        projectionVersion: Int,
    ): Boolean = ledger.existsByEventIdAndProjectionVersion(eventId.value, projectionVersion)

    @Transactional
    override fun deadLetter(
        eventId: EventId,
        projectionVersion: Int,
        reasonCode: String,
        detail: String,
    ) {
        val existing = deadLetters.findByEventId(eventId.value)
        if (existing != null) {
            // 재격리는 멱등 — 사유·시각만 최신으로 갱신(중복 행 없음).
            existing.projectionVersion = projectionVersion
            existing.reasonCode = reasonCode
            existing.detail = detail
            existing.failedAt = clock.instant()
            deadLetters.save(existing)
            return
        }
        deadLetters.save(
            NexaProjectionDeadLetterEntity(
                eventId = eventId.value,
                projectionVersion = projectionVersion,
                reasonCode = reasonCode,
                detail = detail,
                failedAt = clock.instant(),
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun deadLetters(limit: Int): List<DeadLetterRecord> =
        deadLetters
            .findByOrderByFailedAtDescIdDesc(PageRequest.of(0, limit.coerceAtLeast(1)))
            .map { it.toRecord() }

    @Transactional
    override fun clearDeadLetter(eventId: EventId): Boolean {
        val existing = deadLetters.findByEventId(eventId.value) ?: return false
        deadLetters.delete(existing)
        return true
    }

    private fun NexaProjectionDeadLetterEntity.toRecord(): DeadLetterRecord =
        DeadLetterRecord(
            eventId = EventId(eventId),
            projectionVersion = projectionVersion,
            reasonCode = reasonCode,
            detail = detail,
            failedAtEpochMs = failedAt.toEpochMilli(),
        )
}

/**
 * projection 적용 원장 JPA 엔티티(T016). (event_id, projection_version) 유니크로 dedup 을 보장한다.
 * 원문을 담지 않는다(멱등 키·버전·시각만).
 */
@Entity
@Table(name = "nexa_projection_ledger")
class NexaProjectionLedgerEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "event_id") var eventId: String = "",
    @Column(name = "projection_version") var projectionVersion: Int = 0,
    @Column(name = "applied_at") var appliedAt: Instant = Instant.EPOCH,
)

interface NexaProjectionLedgerRepository : JpaRepository<NexaProjectionLedgerEntity, Long> {
    fun existsByEventIdAndProjectionVersion(
        eventId: String,
        projectionVersion: Int,
    ): Boolean
}

/**
 * projection dead-letter JPA 엔티티(T018). 영구 실패·재시도 소진 이벤트를 격리한다. event_id 유니크라
 * 재격리가 한 행만 만든다(멱등). 원문/PII 를 담지 않는다 — event_id·분류 코드·짧은 진단·시각만.
 *
 * data class 가 아니며 [toString] 을 메타데이터만 노출하도록 오버라이드한다(원문 누출 방지, logging-boundary.md).
 */
@Entity
@Table(name = "nexa_projection_dead_letter")
class NexaProjectionDeadLetterEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "event_id") var eventId: String = "",
    @Column(name = "projection_version") var projectionVersion: Int = 0,
    @Column(name = "reason_code") var reasonCode: String = "",
    @Column(name = "detail") var detail: String = "",
    @Column(name = "failed_at") var failedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String =
        "NexaProjectionDeadLetterEntity(eventId=$eventId, projectionVersion=$projectionVersion, reasonCode=$reasonCode)"
}

interface NexaProjectionDeadLetterRepository : JpaRepository<NexaProjectionDeadLetterEntity, Long> {
    fun findByEventId(eventId: String): NexaProjectionDeadLetterEntity?

    fun findByOrderByFailedAtDescIdDesc(page: PageRequest): List<NexaProjectionDeadLetterEntity>
}
