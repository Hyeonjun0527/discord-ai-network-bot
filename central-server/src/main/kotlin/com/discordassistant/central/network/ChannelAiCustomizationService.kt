package com.discordassistant.central.network

import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_ANSWER_LENGTH
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_CONSTITUTION
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_PURPOSE
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_SAFETY_LEVEL
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_TONE
import com.discordassistant.central.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiChangeProposalEntity
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.persistence.CustomizationAuditLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class ChannelAiCustomizationService(
    private val channelAis: ChannelAiRepository,
    private val versions: AiBehaviorVersionRepository,
    private val proposals: AiChangeProposalRepository,
    private val audits: CustomizationAuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun createFromWizard(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        name: String,
        avatarUrl: String?,
        job: String,
        tone: String,
        answerLength: String,
        constitution: String?,
        requireApproval: Boolean,
    ): ChannelAiWizardResult {
        val now = Instant.now(clock)
        val channelAi =
            channelAis.findByGuildIdAndChannelId(guildId, channelId)
                ?: ChannelAiEntity(guildId = guildId, channelId = channelId, source = "wizard", createdAt = now)
        channelAi.displayName = name.trim().take(80).ifBlank { "냥시스턴트" }
        channelAi.avatarUrl = avatarUrl?.trim()?.ifBlank { null }
        channelAi.updatedAt = now
        val savedChannel = channelAis.saveAndFlush(channelAi)

        val previous = savedChannel.activeBehaviorVersionId?.let { versions.findByChannelAiIdAndId(savedChannel.id, it) }
        val nextVersion = (versions.findTopByChannelAiIdOrderByVersionDesc(savedChannel.id)?.version ?: 0) + 1
        val behavior =
            versions.saveAndFlush(
                AiBehaviorVersionEntity(
                    channelAiId = savedChannel.id,
                    version = nextVersion,
                    purpose = normalize(job, previous?.purpose, DEFAULT_CHANNEL_AI_PURPOSE, 200),
                    tone = normalize(tone, previous?.tone, DEFAULT_CHANNEL_AI_TONE, 80),
                    answerLength = normalize(answerLength, previous?.answerLength, DEFAULT_CHANNEL_AI_ANSWER_LENGTH, 40),
                    constitution = normalizeOptional(constitution, previous?.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION, 2000),
                    safetyLevel = previous?.safetyLevel ?: DEFAULT_CHANNEL_AI_SAFETY_LEVEL,
                    createdBy = actorUserId,
                    createdAt = now,
                    changeSummary = "created from channel AI wizard",
                ),
            )
        val status = if (requireApproval) "pending" else "approved"
        if (!requireApproval) {
            savedChannel.activeBehaviorVersionId = behavior.id
            savedChannel.updatedAt = now
            channelAis.save(savedChannel)
        }
        val proposal =
            proposals.save(
                AiChangeProposalEntity(
                    guildId = guildId,
                    channelId = channelId,
                    channelAiId = savedChannel.id,
                    proposedBehaviorId = behavior.id,
                    status = status,
                    requestedBy = actorUserId,
                    reason = "channel AI wizard",
                    createdAt = now,
                    reviewedAt = if (requireApproval) null else now,
                    reviewedBy = if (requireApproval) null else actorUserId,
                ),
            )
        audit(
            guildId = guildId,
            channelId = channelId,
            actorUserId = actorUserId,
            action = if (requireApproval) "propose" else "publish",
            targetType = "ai_behavior_version",
            targetId = behavior.id,
            summary = "wizard created v${behavior.version} status=$status",
        )
        return ChannelAiWizardResult(savedChannel.id, behavior.id, behavior.version, proposal.id, status)
    }

    @Transactional
    fun approveProposal(
        proposalId: Long,
        reviewerUserId: Long?,
    ): AiChangeProposalEntity {
        val proposal = proposals.findById(proposalId).orElseThrow { IllegalArgumentException("proposal not found: $proposalId") }
        require(proposal.status == "pending") { "pending proposal only can be approved" }
        val channelAiId = proposal.channelAiId ?: throw IllegalArgumentException("proposal has no channel ai")
        val behaviorId = proposal.proposedBehaviorId ?: throw IllegalArgumentException("proposal has no behavior")
        val channelAi = channelAis.findById(channelAiId).orElseThrow { IllegalArgumentException("channel ai not found: $channelAiId") }
        channelAi.activeBehaviorVersionId = behaviorId
        channelAi.updatedAt = Instant.now(clock)
        channelAis.save(channelAi)
        proposal.status = "approved"
        proposal.reviewedBy = reviewerUserId
        proposal.reviewedAt = Instant.now(clock)
        val saved = proposals.save(proposal)
        val behavior = versions.findByChannelAiIdAndId(channelAiId, behaviorId)
        audit(
            guildId = proposal.guildId,
            channelId = proposal.channelId,
            actorUserId = reviewerUserId,
            action = "approve",
            targetType = "ai_behavior_version",
            targetId = behaviorId,
            summary = "approved v${behavior?.version ?: "-"}",
        )
        return saved
    }

    @Transactional
    fun rejectProposal(
        proposalId: Long,
        reviewerUserId: Long?,
        reason: String?,
    ): AiChangeProposalEntity {
        val proposal = proposals.findById(proposalId).orElseThrow { IllegalArgumentException("proposal not found: $proposalId") }
        require(proposal.status == "pending") { "pending proposal only can be rejected" }
        proposal.status = "rejected"
        proposal.reviewedBy = reviewerUserId
        proposal.reviewedAt = Instant.now(clock)
        proposal.reason = reason?.trim()?.take(500) ?: proposal.reason
        val saved = proposals.save(proposal)
        audit(
            guildId = proposal.guildId,
            channelId = proposal.channelId,
            actorUserId = reviewerUserId,
            action = "reject",
            targetType = "ai_change_proposal",
            targetId = proposal.id,
            summary = proposal.reason ?: "rejected",
        )
        return saved
    }

    fun pendingProposals(guildId: Long): List<AiChangeProposalEntity> = proposals.findByGuildIdAndStatus(guildId, "pending")

    fun channelHistory(
        guildId: Long,
        channelId: Long,
    ): ChannelAiHistory {
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        val behaviorVersions = channelAi?.let { versions.findByChannelAiIdOrderByVersionDesc(it.id) } ?: emptyList()
        val proposalHistory = proposals.findByGuildIdAndChannelIdOrderByCreatedAtDesc(guildId, channelId)
        val auditHistory = audits.findTop10ByGuildIdAndChannelIdOrderByCreatedAtDesc(guildId, channelId)
        return ChannelAiHistory(channelAi, behaviorVersions, proposalHistory, auditHistory)
    }

    private fun audit(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        action: String,
        targetType: String,
        targetId: Long?,
        summary: String,
    ) {
        audits.save(
            CustomizationAuditLogEntity(
                guildId = guildId,
                channelId = channelId,
                actorId = actorUserId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                summary = summary.take(1000),
                createdAt = Instant.now(clock),
            ),
        )
    }

    private fun normalize(
        value: String?,
        previous: String?,
        default: String,
        max: Int,
    ): String = value?.trim()?.takeIf { it.isNotBlank() }?.take(max) ?: previous ?: default

    private fun normalizeOptional(
        value: String?,
        previous: String?,
        max: Int,
    ): String? = value?.trim()?.takeIf { it.isNotBlank() }?.take(max) ?: previous
}

data class ChannelAiWizardResult(
    val channelAiId: Long,
    val behaviorVersionId: Long,
    val version: Int,
    val proposalId: Long,
    val status: String,
)

data class ChannelAiHistory(
    val channelAi: ChannelAiEntity?,
    val versions: List<AiBehaviorVersionEntity>,
    val proposals: List<AiChangeProposalEntity>,
    val audits: List<CustomizationAuditLogEntity>,
)
