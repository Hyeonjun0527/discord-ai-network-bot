package com.discordassistant.central.speech.adapter.outbound.persistence

import com.discordassistant.central.speech.application.port.out.SpeechDecisionLog
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLogPort
import com.discordassistant.central.speech.application.port.out.SpeechDecisionOutcome
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

@Repository
class JpaSpeechDecisionLog(
    private val logs: NexaSpeechDecisionLogRepository,
) : SpeechDecisionLogPort {
    @Transactional
    override fun record(decision: SpeechDecisionLog) {
        logs.save(decision.toEntity())
    }
}

@Entity
@Table(name = "nexa_speech_decision_log")
class NexaSpeechDecisionLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "decision_id") var decisionId: String? = null,
    @Column(name = "correlation_id") var correlationId: String? = null,
    @Column(name = "focus_thread_key") var focusThreadKey: String = "",
    @Column(name = "social_act") var socialAct: String = "",
    @Column(name = "outcome") var outcome: String = "",
    @Column(name = "blocked_stage") var blockedStage: String? = null,
    @Column(name = "blocked_reason") var blockedReason: String? = null,
    @Column(name = "high_risk_downgraded") var highRiskDowngraded: Boolean = false,
    @Column(name = "consent_blocked") var consentBlocked: Boolean = false,
    @Column(name = "generated_candidate_count") var generatedCandidateCount: Int = 0,
    @Column(name = "critic_reasons_json", length = 2048) var criticReasonsJson: String = "[]",
    @Column(name = "selected_content_ref") var selectedContentRef: String? = null,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String =
        "NexaSpeechDecisionLogEntity(decisionId=$decisionId, outcome=$outcome, blockedStage=$blockedStage, createdAt=$createdAt)"
}

interface NexaSpeechDecisionLogRepository : JpaRepository<NexaSpeechDecisionLogEntity, Long> {
    fun findFirstByCorrelationIdOrderByCreatedAtDesc(correlationId: String): NexaSpeechDecisionLogEntity?
}

private fun SpeechDecisionLog.toEntity(): NexaSpeechDecisionLogEntity =
    NexaSpeechDecisionLogEntity(
        decisionId = decisionId,
        correlationId = correlationId,
        focusThreadKey = focusThreadKey,
        socialAct = socialAct.wireName,
        outcome = outcome.name,
        blockedStage = blockedStage,
        blockedReason = blockedReason,
        highRiskDowngraded = highRiskDowngraded,
        consentBlocked = consentBlocked,
        generatedCandidateCount = generatedCandidateCount,
        criticReasonsJson = criticBlockReasons.toJsonArray(),
        selectedContentRef = selectedContentRef,
        createdAt = createdAt,
    )

fun NexaSpeechDecisionLogEntity.toDomain(): SpeechDecisionLog =
    SpeechDecisionLog(
        decisionId = decisionId,
        correlationId = correlationId,
        focusThreadKey = focusThreadKey,
        socialAct = SpeechSocialAct.fromWireName(socialAct),
        outcome = SpeechDecisionOutcome.valueOf(outcome),
        blockedStage = blockedStage,
        blockedReason = blockedReason,
        highRiskDowngraded = highRiskDowngraded,
        consentBlocked = consentBlocked,
        generatedCandidateCount = generatedCandidateCount,
        criticBlockReasons = criticReasonsJson.toJsonSet(),
        selectedContentRef = selectedContentRef,
        createdAt = createdAt,
    )

private val jsonMapper = jacksonObjectMapper()
private val stringListType = object : TypeReference<List<String>>() {}

private fun Set<String>.toJsonArray(): String = jsonMapper.writeValueAsString(toList().sorted())

private fun String.toJsonSet(): Set<String> =
    if (isBlank()) {
        emptySet()
    } else {
        runCatching { jsonMapper.readValue(this, stringListType).toSet() }.getOrDefault(emptySet())
    }
