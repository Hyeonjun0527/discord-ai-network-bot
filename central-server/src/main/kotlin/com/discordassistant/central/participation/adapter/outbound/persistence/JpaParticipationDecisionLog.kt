package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.port.out.DecisionLogRecord
import com.discordassistant.central.participation.application.port.out.ParticipationDecisionLogPort
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [ParticipationDecisionLogPort] 의 JPA 구현 어댑터(NEXA-P08-T023, Flyway V58). 모든 참여 결정(IGNORE 포함)을
 * **원문 없이** feature hash·결정 provenance 로 영속화한다(logging-boundary.md).
 *
 * **멱등 append(acceptance T023)**: correlation_id 유니크로 처음이면 insert, 있으면 갱신 — 같은 결정을 N 번
 * 기록해도 한 행으로 수렴한다.
 *
 * **IGNORE 저장·보존 정책(acceptance T023)**: [append] 는 action kind 와 무관하게 저장한다. [purgeExpired] 가
 * decided_at 기준으로 보존 기간 초과 행을 삭제한다.
 *
 * **원문 비저장**: feature hash·안정 코드·가명·seed·시각만(원본 feature/원문/snowflake 비저장). [toString] 을
 * 메타데이터만 노출하도록 오버라이드한다(누출 방지).
 */
@Repository
class JpaParticipationDecisionLog(
    private val logs: NexaPolicyDecisionLogRepository,
) : ParticipationDecisionLogPort {
    @Transactional
    override fun append(record: DecisionLogRecord) {
        val entity =
            logs.findByCorrelationId(record.correlationId)
                ?: NexaPolicyDecisionLogEntity(correlationId = record.correlationId)
        entity.guildPseudonym = record.guildPseudonym
        entity.channelId = record.channelId
        entity.contextVersion = record.contextVersion
        entity.actionKind = record.actionKind.wireName
        entity.featureHash = record.featureHash
        entity.featureVectorVersion = record.featureVectorVersion
        entity.modelVersion = record.modelVersion
        entity.seed = record.seed
        entity.removedKinds = record.removedKinds.joinToString(",") { it.wireName }
        entity.reasonCode = record.reasonCode
        entity.judgeConfidence = record.judgeConfidence
        entity.decisionDelayMillis = record.decisionDelayMillis
        entity.lastWakeUpReason = record.lastWakeUpReason
        entity.missingInputCodes = record.missingInputCodes.joinToString(",")
        entity.evidenceRefs = record.evidenceRefs.joinToString(",")
        entity.consumedGenerationQuota = record.consumedGenerationQuota
        entity.decidedAt = record.decidedAt
        logs.save(entity)
    }

    @Transactional(readOnly = true)
    override fun findByCorrelationId(correlationId: String): DecisionLogRecord? = logs.findByCorrelationId(correlationId)?.toDomain()

    @Transactional
    override fun purgeExpired(olderThan: Instant): Int = logs.deleteByDecidedAtBefore(olderThan)

    private fun NexaPolicyDecisionLogEntity.toDomain(): DecisionLogRecord =
        DecisionLogRecord(
            correlationId = correlationId,
            guildPseudonym = guildPseudonym,
            channelId = channelId,
            contextVersion = contextVersion,
            actionKind = SocialActionKind.entries.first { it.wireName == actionKind },
            featureHash = featureHash,
            featureVectorVersion = featureVectorVersion,
            modelVersion = modelVersion,
            seed = seed,
            removedKinds =
                removedKinds
                    .split(",")
                    .filter { it.isNotBlank() }
                    .mapNotNull { code -> SocialActionKind.entries.firstOrNull { it.wireName == code } }
                    .toSet(),
            reasonCode = reasonCode,
            judgeConfidence = judgeConfidence,
            decisionDelayMillis = decisionDelayMillis,
            lastWakeUpReason = lastWakeUpReason,
            missingInputCodes = missingInputCodes.toCodeSet(),
            evidenceRefs = evidenceRefs.toCodeSet(),
            consumedGenerationQuota = consumedGenerationQuota,
            decidedAt = decidedAt,
        )
}

/**
 * 참여 결정 로그 JPA 엔티티(T023). correlation_id 유니크로 멱등 append. 원문/원본 feature 를 담지 않는다(feature
 * hash·안정 코드·가명·seed·시각만). [toString] 을 메타데이터만 노출하도록 오버라이드한다.
 */
@Entity
@Table(name = "nexa_policy_decision_log")
class NexaPolicyDecisionLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "correlation_id") var correlationId: String = "",
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "channel_id") var channelId: String = "",
    @Column(name = "context_version") var contextVersion: Long = 0,
    @Column(name = "action_kind") var actionKind: String = "",
    @Column(name = "feature_hash") var featureHash: String = "",
    @Column(name = "feature_vector_version") var featureVectorVersion: Int = 1,
    @Column(name = "model_version") var modelVersion: String = "",
    @Column(name = "seed") var seed: Long = 0,
    @Column(name = "removed_kinds") var removedKinds: String = "",
    @Column(name = "reason_code") var reasonCode: String? = null,
    @Column(name = "judge_confidence") var judgeConfidence: Double? = null,
    @Column(name = "decision_delay_millis") var decisionDelayMillis: Long? = null,
    @Column(name = "last_wake_up_reason") var lastWakeUpReason: String? = null,
    @Column(name = "missing_input_codes") var missingInputCodes: String = "",
    @Column(name = "evidence_refs") var evidenceRefs: String = "",
    @Column(name = "consumed_generation_quota") var consumedGenerationQuota: Boolean = false,
    @Column(name = "decided_at") var decidedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String =
        "NexaPolicyDecisionLogEntity(correlationId=$correlationId, actionKind=$actionKind, decidedAt=$decidedAt)"
}

interface NexaPolicyDecisionLogRepository : JpaRepository<NexaPolicyDecisionLogEntity, Long> {
    fun findByCorrelationId(correlationId: String): NexaPolicyDecisionLogEntity?

    @Modifying
    @Query("DELETE FROM NexaPolicyDecisionLogEntity e WHERE e.decidedAt < :olderThan")
    fun deleteByDecidedAtBefore(
        @Param("olderThan") olderThan: Instant,
    ): Int
}

private fun String.toCodeSet(): Set<String> =
    split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
