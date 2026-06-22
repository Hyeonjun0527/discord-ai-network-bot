package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.model.ApprovalAction
import com.discordassistant.central.participation.application.model.ApprovalAuditEntry
import com.discordassistant.central.participation.application.model.ModelApprovalAuditPort
import com.discordassistant.central.participation.application.model.ModelApprovalAuditQueryPort
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * 모델 승인 audit 의 JPA 어댑터(NEXA-P19-T020, Flyway V69). 누가·언제·무엇을·왜 승인/거절했는지 영속한다.
 *
 * 어댑터는 순수 저장/조회만 한다 — 이중 확인·rollback target 불변식은 application
 * [com.discordassistant.central.participation.application.model.ModelApprovalService] 가 강제한다.
 *
 * 원문 비저장: 모델 신원(id)·주체·action·사유 코드만.
 */
@Repository
class JpaModelApprovalAuditStore(
    private val repository: NexaModelApprovalAuditRepository,
) : ModelApprovalAuditPort,
    ModelApprovalAuditQueryPort {
    @Transactional
    override fun record(entry: ApprovalAuditEntry) {
        repository.save(
            NexaModelApprovalAuditEntity(
                modelId = entry.modelId,
                approverId = entry.approverId,
                action = entry.action.name,
                rollbackTargetModelId = entry.rollbackTargetModelId,
                reason = entry.reason,
                decidedAt = entry.at,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun historyFor(modelId: String): List<ApprovalAuditEntry> =
        repository.findByModelIdOrderByDecidedAtDesc(modelId).map { it.toDomain() }
}

/** 모델 승인 audit 행(append-only). */
@Entity
@Table(name = "nexa_model_approval_audit")
class NexaModelApprovalAuditEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "model_id") var modelId: String = "",
    @Column(name = "approver_id") var approverId: String = "",
    @Column(name = "action") var action: String = "",
    @Column(name = "rollback_target_model_id") var rollbackTargetModelId: String? = null,
    @Column(name = "reason") var reason: String? = null,
    @Column(name = "decided_at") var decidedAt: java.time.Instant = java.time.Instant.EPOCH,
) {
    fun toDomain(): ApprovalAuditEntry =
        ApprovalAuditEntry(
            modelId = modelId,
            approverId = approverId,
            action = ApprovalAction.valueOf(action),
            rollbackTargetModelId = rollbackTargetModelId,
            reason = reason,
            at = decidedAt,
        )

    override fun toString(): String = "NexaModelApprovalAuditEntity(modelId=$modelId, action=$action)"
}

interface NexaModelApprovalAuditRepository : JpaRepository<NexaModelApprovalAuditEntity, Long> {
    fun findByModelIdOrderByDecidedAtDesc(modelId: String): List<NexaModelApprovalAuditEntity>
}
