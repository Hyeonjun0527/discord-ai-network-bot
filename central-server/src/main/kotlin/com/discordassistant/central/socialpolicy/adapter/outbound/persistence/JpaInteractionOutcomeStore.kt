package com.discordassistant.central.socialpolicy.adapter.outbound.persistence

import com.discordassistant.central.socialpolicy.application.port.out.InteractionOutcomePort
import com.discordassistant.central.socialpolicy.domain.model.ObservedInteractionOutcome
import com.discordassistant.central.socialpolicy.domain.model.ObservedOutcomeCode
import com.discordassistant.central.socialpolicy.domain.model.UnresolvedInteraction
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 같은 focus의 가장 최근 열린 니아 행동에 제한 시간 안의 다음 사람 반응만 연결한다. */
@Repository
class JpaInteractionOutcomeStore(
    private val interactions: UnresolvedInteractionRepository,
    private val outcomes: ObservedInteractionOutcomeRepository,
) : InteractionOutcomePort {
    @Transactional
    override fun open(interaction: UnresolvedInteraction): Boolean {
        if (interactions.findByActionId(interaction.actionId) != null) return false
        interactions.save(
            UnresolvedInteractionEntity(
                actionId = interaction.actionId,
                focusThreadKey = interaction.focusThreadKey,
                actionKind = interaction.actionKind,
                intentSummary = interaction.intentSummary,
                sourceEvidenceRef = interaction.sourceEvidenceRef,
                sentMessageRef = interaction.sentMessageRef,
                openedAt = interaction.openedAt,
                expiresAt = interaction.expiresAt,
            ),
        )
        return true
    }

    @Transactional
    override fun observeLatest(
        focusThreadKey: String,
        code: ObservedOutcomeCode,
        evidenceRef: String,
        replyToMessageRef: String?,
        observedAt: Instant,
        explicitActionId: String?,
    ): ObservedInteractionOutcome? {
        val interaction =
            when {
                explicitActionId != null -> interactions.findOpenByActionForUpdate(focusThreadKey, explicitActionId).firstOrNull()
                replyToMessageRef != null -> interactions.findOpenBySentMessageForUpdate(focusThreadKey, replyToMessageRef).firstOrNull()
                else -> interactions.findLatestOpenForUpdate(focusThreadKey).firstOrNull()
            } ?: return null
        if (observedAt.isAfter(interaction.expiresAt)) {
            interaction.status = STATUS_EXPIRED
            return null
        }
        interaction.status = STATUS_RESOLVED
        interaction.resolvedAt = observedAt
        val outcome =
            outcomes.save(
                ObservedInteractionOutcomeEntity(
                    actionId = interaction.actionId,
                    code = code.name,
                    evidenceRef = evidenceRef,
                    observedAt = observedAt,
                ),
            )
        return ObservedInteractionOutcome(outcome.actionId, code, outcome.evidenceRef, outcome.observedAt)
    }

    @Transactional
    override fun invalidateByEvidence(evidenceRef: String): Int {
        var changed = 0
        interactions.findBySourceEvidenceRef(evidenceRef).forEach { interaction ->
            if (interaction.status != STATUS_INVALIDATED) {
                interaction.status = STATUS_INVALIDATED
                changed++
            }
        }
        outcomes.findByEvidenceRef(evidenceRef).forEach { outcome ->
            if (outcome.status != STATUS_INVALIDATED) {
                outcome.status = STATUS_INVALIDATED
                changed++
            }
        }
        return changed
    }

    private companion object {
        const val STATUS_OPEN: String = "OPEN"
        const val STATUS_RESOLVED: String = "RESOLVED"
        const val STATUS_EXPIRED: String = "EXPIRED"
        const val STATUS_INVALIDATED: String = "INVALIDATED"
    }
}

@Entity
@Table(name = "nexa_unresolved_interaction")
class UnresolvedInteractionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "action_id") var actionId: String = "",
    @Column(name = "focus_thread_key") var focusThreadKey: String = "",
    @Column(name = "action_kind") var actionKind: String = "",
    @Column(name = "intent_summary") var intentSummary: String? = null,
    @Column(name = "source_evidence_ref") var sourceEvidenceRef: String = "",
    @Column(name = "sent_message_ref") var sentMessageRef: String? = null,
    @Column(name = "status") var status: String = "OPEN",
    @Column(name = "opened_at") var openedAt: Instant = Instant.EPOCH,
    @Column(name = "expires_at") var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "resolved_at") var resolvedAt: Instant? = null,
)

interface UnresolvedInteractionRepository : JpaRepository<UnresolvedInteractionEntity, Long> {
    fun findByActionId(actionId: String): UnresolvedInteractionEntity?

    fun findBySourceEvidenceRef(sourceEvidenceRef: String): List<UnresolvedInteractionEntity>

    fun findTop1000ByExpiresAtBeforeOrderByExpiresAtAsc(cutoff: Instant): List<UnresolvedInteractionEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select i from UnresolvedInteractionEntity i where i.focusThreadKey = :focus and i.status = 'OPEN' order by i.openedAt desc",
    )
    fun findLatestOpenForUpdate(
        @Param("focus") focusThreadKey: String,
    ): List<UnresolvedInteractionEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select i from UnresolvedInteractionEntity i where i.focusThreadKey = :focus and i.sentMessageRef = :sentMessageRef and i.status = 'OPEN' order by i.openedAt desc",
    )
    fun findOpenBySentMessageForUpdate(
        @Param("focus") focusThreadKey: String,
        @Param("sentMessageRef") sentMessageRef: String,
    ): List<UnresolvedInteractionEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select i from UnresolvedInteractionEntity i where i.focusThreadKey = :focus and i.actionId = :actionId and i.status = 'OPEN'",
    )
    fun findOpenByActionForUpdate(
        @Param("focus") focusThreadKey: String,
        @Param("actionId") actionId: String,
    ): List<UnresolvedInteractionEntity>
}

@Entity
@Table(name = "nexa_observed_interaction_outcome")
class ObservedInteractionOutcomeEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "action_id") var actionId: String = "",
    @Column(name = "code") var code: String = "",
    @Column(name = "evidence_ref") var evidenceRef: String = "",
    @Column(name = "status") var status: String = "ACTIVE",
    @Column(name = "observed_at") var observedAt: Instant = Instant.EPOCH,
)

interface ObservedInteractionOutcomeRepository : JpaRepository<ObservedInteractionOutcomeEntity, Long> {
    fun findByEvidenceRef(evidenceRef: String): List<ObservedInteractionOutcomeEntity>

    fun findByActionIdIn(actionIds: Collection<String>): List<ObservedInteractionOutcomeEntity>
}
