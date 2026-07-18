package com.discordassistant.central.actionruntime.adapter.outbound.persistence

import com.discordassistant.central.actionruntime.application.port.out.WaitReevaluationCommand
import com.discordassistant.central.actionruntime.application.port.out.WaitReevaluationOutboxPort
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.global.crypto.FieldCrypto
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** WAIT 완료와 child 판단 요청을 한 transaction으로 영속한다. */
@Repository
class JpaWaitReevaluationOutbox(
    private val actions: ScheduledActionRepository,
    private val outbox: WaitReevaluationOutboxRepository,
    private val clock: Clock = Clock.systemUTC(),
) : WaitReevaluationOutboxPort {
    @Transactional
    override fun completeAndEnqueue(
        action: ScheduledSocialAction,
        observedContextVersion: Long,
    ): WaitReevaluationCommand? {
        require(action.type == ScheduledActionType.WAIT) { "WAIT 행동만 재평가 outbox에 넣을 수 있다" }
        val entity = actions.findByIdentity(action.identity.value) ?: return null
        val existing = outbox.findByWaitActionIdentity(action.identity.value)
        if (existing != null) return existing.toCommand()
        if (ActionStatus.valueOf(entity.status).isTerminal) return null

        val expiresAt = action.expiresAt ?: return null
        val now = Instant.now(clock)
        if (!now.isBefore(expiresAt) || action.waitAttempt >= action.maxAttempts) return null
        val attempt = action.waitAttempt + 1
        val childId = childDecisionId(action.identity.value, attempt, observedContextVersion)
        val row =
            WaitReevaluationOutboxEntity(
                childDecisionId = childId,
                waitActionIdentity = action.identity.value,
                guildPseudonym = action.target.guildPseudonym,
                channelId = action.target.channelId,
                threadId = action.target.threadId,
                subjectPseudonym = action.target.subjectPseudonym,
                // scheduled row가 소유한 암호문을 그대로 전달한다. 키 장애 중 domain target이 null이어도 원본을 잃지 않고,
                // 키 복구 뒤 outbox publisher가 같은 암호문을 다시 복호화할 수 있다.
                targetMessageId = entity.targetMessageId,
                routingGuildId = entity.routingGuildId,
                routingChannelId = entity.routingChannelId,
                routingUserId = entity.routingUserId,
                observedContextVersion = observedContextVersion,
                wakeAttempt = attempt,
                wakeUpHint = action.wakeUpHint,
                expiresAt = expiresAt,
                createdAt = now,
            )
        outbox.save(row)
        entity.status = ActionStatus.COMPLETED.name
        entity.leaseOwner = null
        entity.leaseExpiresAt = null
        entity.updatedAt = now
        return row.toCommand()
    }

    @Transactional
    override fun claimPending(limit: Int): List<WaitReevaluationCommand> {
        val now = Instant.now(clock)
        return outbox.lockPending(now.minus(CLAIM_LEASE), limit).map { row ->
            row.status = OUTBOX_CLAIMED
            row.claimedAt = now
            row.toCommand()
        }
    }

    @Transactional
    override fun markPublished(childDecisionId: String): Boolean {
        val row = outbox.findByChildDecisionId(childDecisionId) ?: return false
        if (row.status != OUTBOX_CLAIMED) return false
        row.status = OUTBOX_PUBLISHED
        row.claimedAt = null
        row.publishedAt = Instant.now(clock)
        return true
    }

    @Transactional
    override fun releaseClaim(childDecisionId: String): Boolean {
        val row = outbox.findByChildDecisionId(childDecisionId) ?: return false
        if (row.status != OUTBOX_CLAIMED) return false
        row.status = OUTBOX_PENDING
        row.claimedAt = null
        return true
    }

    private fun WaitReevaluationOutboxEntity.toCommand(): WaitReevaluationCommand =
        WaitReevaluationCommand(
            childDecisionId = childDecisionId,
            waitActionIdentity = waitActionIdentity,
            guildPseudonym = guildPseudonym,
            channelId = channelId,
            threadId = threadId,
            subjectPseudonym = subjectPseudonym,
            targetMessageId = FieldCrypto.decryptOrNull(targetMessageId),
            routingGuildId = FieldCrypto.decryptOrNull(routingGuildId),
            routingChannelId = FieldCrypto.decryptOrNull(routingChannelId),
            routingUserId = FieldCrypto.decryptOrNull(routingUserId),
            observedContextVersion = observedContextVersion,
            wakeAttempt = wakeAttempt,
            wakeUpHint = wakeUpHint,
            expiresAt = expiresAt,
        )

    companion object {
        private const val OUTBOX_PENDING = "PENDING"
        private const val OUTBOX_CLAIMED = "CLAIMED"
        private const val OUTBOX_PUBLISHED = "PUBLISHED"
        private val CLAIM_LEASE: Duration = Duration.ofMinutes(1)

        private fun childDecisionId(
            actionIdentity: String,
            attempt: Int,
            contextVersion: Long,
        ): String {
            val raw = "$actionIdentity:$attempt:$contextVersion"
            return MessageDigest
                .getInstance("SHA-256")
                .digest(raw.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

@Entity
@Table(name = "nexa_wait_reevaluation_outbox")
class WaitReevaluationOutboxEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "child_decision_id") var childDecisionId: String = "",
    @Column(name = "wait_action_identity") var waitActionIdentity: String = "",
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "channel_id") var channelId: String = "",
    @Column(name = "thread_id") var threadId: String = "",
    @Column(name = "subject_pseudonym") var subjectPseudonym: String? = null,
    @Column(name = "target_message_id") var targetMessageId: String? = null,
    @Column(name = "routing_guild_id") var routingGuildId: String? = null,
    @Column(name = "routing_channel_id") var routingChannelId: String? = null,
    @Column(name = "routing_user_id") var routingUserId: String? = null,
    @Column(name = "observed_context_version") var observedContextVersion: Long = 0,
    @Column(name = "wake_attempt") var wakeAttempt: Int = 0,
    @Column(name = "wake_up_hint") var wakeUpHint: String? = null,
    @Column(name = "expires_at") var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "status") var status: String = "PENDING",
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
    @Column(name = "claimed_at") var claimedAt: Instant? = null,
    @Column(name = "published_at") var publishedAt: Instant? = null,
)

interface WaitReevaluationOutboxRepository : JpaRepository<WaitReevaluationOutboxEntity, Long> {
    fun findByChildDecisionId(childDecisionId: String): WaitReevaluationOutboxEntity?

    fun findByWaitActionIdentity(waitActionIdentity: String): WaitReevaluationOutboxEntity?

    fun findTop1000ByExpiresAtBeforeOrderByExpiresAtAsc(cutoff: Instant): List<WaitReevaluationOutboxEntity>

    @org.springframework.data.jpa.repository.Query(
        value =
            "SELECT * FROM nexa_wait_reevaluation_outbox " +
                "WHERE status = 'PENDING' OR (status = 'CLAIMED' AND claimed_at < :claimBefore) " +
                "ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
        nativeQuery = true,
    )
    fun lockPending(
        @org.springframework.data.repository.query.Param("claimBefore") claimBefore: Instant,
        @org.springframework.data.repository.query.Param("limit") limit: Int,
    ): List<WaitReevaluationOutboxEntity>
}
