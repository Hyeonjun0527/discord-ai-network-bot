package com.discordassistant.central.dashboard

import com.discordassistant.central.network.ChannelAiCustomizationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai-network/channel-ai")
class ChannelAiCustomizationController(
    private val customization: ChannelAiCustomizationService,
) {
    @PostMapping("/wizard/draft")
    fun draft(
        @RequestBody request: ChannelAiWizardDraftRequest,
    ): Map<String, Any?> {
        val draft =
            customization.draftFromAnswers(
                job = request.job,
                tone = request.tone,
                answerLength = request.answerLength,
                customName = request.name,
            )
        return mapOf(
            "name" to draft.name,
            "job" to draft.job,
            "tone" to draft.tone,
            "answerLength" to draft.answerLength,
            "constitution" to draft.constitution,
            "preview" to draft.preview,
        )
    }

    @PostMapping("/{guildId}/{channelId}/wizard")
    fun createFromWizard(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: ChannelAiWizardRequest,
    ): Map<String, Any?> {
        val result =
            customization.createFromWizard(
                guildId = guildId,
                channelId = channelId,
                actorUserId = request.actorUserId,
                name = request.name,
                avatarUrl = request.avatarUrl,
                job = request.job,
                tone = request.tone,
                answerLength = request.answerLength,
                constitution = request.constitution,
                requireApproval = request.requireApproval,
            )
        return mapOf(
            "channelAiId" to result.channelAiId,
            "behaviorVersionId" to result.behaviorVersionId,
            "version" to result.version,
            "proposalId" to result.proposalId,
            "status" to result.status,
            "approvalReason" to result.approvalReason,
        )
    }

    @PostMapping("/{guildId}/{channelId}/rollback")
    fun rollback(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: RollbackChannelAiVersionRequest,
    ): Map<String, Any?> {
        val result =
            customization.rollbackToVersion(
                guildId = guildId,
                channelId = channelId,
                targetVersion = request.targetVersion,
                actorUserId = request.actorUserId,
                requireApproval = request.requireApproval,
                reason = request.reason,
            )
        return mapOf(
            "channelAiId" to result.channelAiId,
            "behaviorVersionId" to result.behaviorVersionId,
            "version" to result.version,
            "proposalId" to result.proposalId,
            "status" to result.status,
            "approvalReason" to result.approvalReason,
        )
    }

    @PostMapping("/proposals/{proposalId}/approve")
    fun approve(
        @PathVariable proposalId: Long,
        @RequestBody request: ReviewChannelAiProposalRequest,
    ): Map<String, Any?> {
        val proposal = customization.approveProposal(proposalId, request.reviewerUserId)
        return mapOf("id" to proposal.id, "status" to proposal.status, "reviewedBy" to proposal.reviewedBy)
    }

    @PostMapping("/proposals/{proposalId}/reject")
    fun reject(
        @PathVariable proposalId: Long,
        @RequestBody request: ReviewChannelAiProposalRequest,
    ): Map<String, Any?> {
        val proposal = customization.rejectProposal(proposalId, request.reviewerUserId, request.reason)
        return mapOf("id" to proposal.id, "status" to proposal.status, "reason" to proposal.reason)
    }

    @GetMapping("/{guildId}/proposals/pending")
    fun pending(
        @PathVariable guildId: Long,
    ): List<Map<String, Any?>> =
        customization.pendingProposals(guildId).map {
            mapOf(
                "id" to it.id,
                "channelId" to it.channelId,
                "channelAiId" to it.channelAiId,
                "proposedBehaviorId" to it.proposedBehaviorId,
                "requestedBy" to it.requestedBy,
                "createdAt" to it.createdAt.toString(),
            )
        }

    @GetMapping("/{guildId}/{channelId}/onboarding")
    fun onboarding(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
    ) = customization.channelOnboarding(guildId, channelId)

    @GetMapping("/{guildId}/{channelId}/history")
    fun history(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
    ): Map<String, Any?> {
        val history = customization.channelHistory(guildId, channelId)
        return mapOf(
            "channelAiId" to history.channelAi?.id,
            "activeBehaviorVersionId" to history.channelAi?.activeBehaviorVersionId,
            "versions" to
                history.versions.map {
                    mapOf(
                        "id" to it.id,
                        "version" to it.version,
                        "purpose" to it.purpose,
                        "tone" to it.tone,
                        "answerLength" to it.answerLength,
                        "createdAt" to it.createdAt.toString(),
                    )
                },
            "proposals" to
                history.proposals.map {
                    mapOf(
                        "id" to it.id,
                        "status" to it.status,
                        "proposedBehaviorId" to it.proposedBehaviorId,
                        "requestedBy" to it.requestedBy,
                        "reviewedBy" to it.reviewedBy,
                    )
                },
            "audits" to history.audits.map { "${it.action}:${it.targetType}:${it.targetId ?: "-"}" },
        )
    }
}

data class ChannelAiWizardDraftRequest(
    val job: String,
    val tone: String = "friendly",
    val answerLength: String = "balanced",
    val name: String? = null,
)

data class ChannelAiWizardRequest(
    val actorUserId: Long? = null,
    val name: String,
    val avatarUrl: String? = null,
    val job: String,
    val tone: String = "friendly",
    val answerLength: String = "balanced",
    val constitution: String? = null,
    val requireApproval: Boolean = false,
)

data class RollbackChannelAiVersionRequest(
    val targetVersion: Int,
    val actorUserId: Long? = null,
    val requireApproval: Boolean = false,
    val reason: String? = null,
)

data class ReviewChannelAiProposalRequest(
    val reviewerUserId: Long? = null,
    val reason: String? = null,
)
