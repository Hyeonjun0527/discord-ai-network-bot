package com.discordassistant.central.actionruntime.adapter.outbound.persistence

import com.discordassistant.central.actionruntime.application.port.out.ActionAuditPort
import com.discordassistant.central.actionruntime.domain.model.ActionAuditEvent
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [ActionAuditPort] 의 JPA 구현(NEXA-P13-T022, Flyway V64).
 *
 * 예약 행동의 모든 상태 변경을 `nexa_action_audit` 에 **append-only** 로 적재한다. [append] 는 INSERT 만 하고 기존
 * 행을 수정/삭제하지 않는다(감사 무결성). [findByAction] 은 한 행동의 사건을 occurred_at 순으로 모아 생애를 재구성한다
 * (acceptance T022 — 원문 없이 decision/action/message IDs 로 재구성).
 *
 * 순수성: 도메인/application 타입 ↔ entity 매핑만. 도메인은 이 어댑터를 모른다(헥사고날).
 */
@Repository
class JpaActionAuditStore(
    private val repo: ActionAuditRepository,
) : ActionAuditPort {
    @Transactional
    override fun append(event: ActionAuditEvent) {
        repo.save(event.toEntity()) // append-only — 새 행만 추가, 기존 불변.
    }

    @Transactional(readOnly = true)
    override fun findByAction(actionId: String): List<ActionAuditEvent> =
        repo.findByActionIdOrderByOccurredAtAscIdAsc(actionId).map { it.toDomain() }

    private fun ActionAuditEvent.toEntity(): ActionAuditEntity =
        ActionAuditEntity(
            actionId = actionId,
            decisionId = decisionId,
            phase = phase.wireName,
            messageId = messageId,
            reason = reason,
            occurredAt = occurredAt,
        )

    private fun ActionAuditEntity.toDomain(): ActionAuditEvent =
        ActionAuditEvent(
            actionId = actionId,
            decisionId = decisionId,
            phase = ActionAuditPhase.entries.first { it.wireName == phase },
            messageId = messageId,
            reason = reason,
            occurredAt = occurredAt,
        )
}

/** 감사 사건 영속 엔티티(`nexa_action_audit`, append-only). */
@Entity
@Table(name = "nexa_action_audit")
class ActionAuditEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "action_id") var actionId: String = "",
    @Column(name = "decision_id") var decisionId: String = "",
    @Column(name = "phase") var phase: String = "",
    @Column(name = "message_id") var messageId: String? = null,
    @Column(name = "reason") var reason: String? = null,
    @Column(name = "occurred_at") var occurredAt: Instant = Instant.EPOCH,
)

interface ActionAuditRepository : JpaRepository<ActionAuditEntity, Long> {
    /** 한 행동의 사건을 occurred_at(동시각이면 삽입순 id) 오름차순으로 — 생애 재구성. */
    fun findByActionIdOrderByOccurredAtAscIdAsc(actionId: String): List<ActionAuditEntity>
}
