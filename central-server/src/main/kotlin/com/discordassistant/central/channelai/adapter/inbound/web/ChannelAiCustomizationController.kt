package com.discordassistant.central.channelai.adapter.inbound.web

import com.discordassistant.central.channelai.application.AiAdminRolePolicy
import com.discordassistant.central.channelai.application.ChannelAiCustomizationService
import com.discordassistant.central.web.DashboardActor
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대시보드 채널 AI 관리 API.
 *
 * 보안(#1): 권한/신원 플래그(`isGuildAdmin`/`roleIds`/`userId`)는 **요청 body 에서 받지 않는다**.
 * [AiNetworkApiSecurityFilter] 가 세운 서버측 인증 주체([DashboardActor])에서 actor 를 유도한다.
 * 이 엔드포인트들은 필터의 admin 게이트 뒤에 있으므로, **도달했다 = 신뢰된 전역 대시보드 관리자**다
 * (self-hosted 단일 운영자 모델). 따라서 서버가 `isGuildAdmin=true` 로 권한을 부여하고,
 * actor user id 는 audit 추적성을 위해 인증 주체에서 가져온다. 클라이언트가 보낸 어떤 플래그도
 * 권한 부여에 쓰이지 않는다.
 */
@RestController
@RequestMapping("/api/ai-network/channel-ai")
class ChannelAiCustomizationController(
    private val customization: ChannelAiCustomizationService,
) {
    /**
     * 신뢰된 대시보드 관리자는 길드 단위 AI-admin 역할 제약을 우회한다(per-guild 역할은 Discord 슬래시
     * 명령 가드레일이지 전역 운영자를 막기 위한 것이 아니다). 따라서 서버측 actor 의 roleIds 는 비우고
     * `actorIsGuildAdmin=true` 로 호출한다 — body 가 아니라 인증 주체가 권한의 단일 출처다.
     */
    private fun actorOf(request: HttpServletRequest): DashboardActor = DashboardActor.from(request)

    @GetMapping("/wizard/options")
    fun wizardOptions() = customization.wizardOptions()

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
        httpRequest: HttpServletRequest,
    ): Map<String, Any?> {
        val actor = actorOf(httpRequest)
        val result =
            customization.createFromWizard(
                guildId = guildId,
                channelId = channelId,
                // 권한/신원은 인증 주체에서 유도(body 불신). actor user id 는 audit 추적성용.
                actorUserId = actor.userId,
                actorRoleIds = emptySet(),
                actorIsGuildAdmin = true,
                name = request.name,
                avatarUrl = request.avatarUrl,
                job = request.job,
                tone = request.tone,
                answerLength = request.answerLength,
                constitution = request.constitution,
                // 즉시 active 우회 차단(#1): 검토 강제가 기본. body 로 false 를 줘도 검토를 끌 수 없다.
                requireApproval = true,
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
        httpRequest: HttpServletRequest,
    ): Map<String, Any?> {
        val actor = actorOf(httpRequest)
        val result =
            customization.rollbackToVersion(
                guildId = guildId,
                channelId = channelId,
                targetVersion = request.targetVersion,
                // 권한/신원은 인증 주체에서 유도(body 불신).
                actorUserId = actor.userId,
                actorRoleIds = emptySet(),
                actorIsGuildAdmin = true,
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
        httpRequest: HttpServletRequest,
    ): Map<String, Any?> {
        val actor = actorOf(httpRequest)
        val proposal =
            customization.approveProposal(
                proposalId = proposalId,
                // 검토자 권한/신원은 인증 주체에서 유도(body 불신).
                reviewerUserId = actor.userId,
                reviewerRoleIds = emptySet(),
                reviewerIsGuildAdmin = true,
                reason = request.reason,
            )
        return mapOf(
            "id" to proposal.id,
            "status" to proposal.status,
            "reviewedBy" to proposal.reviewedBy,
            "reason" to proposal.reason,
        )
    }

    @PostMapping("/proposals/{proposalId}/reject")
    fun reject(
        @PathVariable proposalId: Long,
        @RequestBody request: ReviewChannelAiProposalRequest,
        httpRequest: HttpServletRequest,
    ): Map<String, Any?> {
        val actor = actorOf(httpRequest)
        val proposal =
            customization.rejectProposal(
                proposalId = proposalId,
                // 검토자 권한/신원은 인증 주체에서 유도(body 불신).
                reviewerUserId = actor.userId,
                reviewerRoleIds = emptySet(),
                reviewerIsGuildAdmin = true,
                reason = request.reason,
            )
        return mapOf("id" to proposal.id, "status" to proposal.status, "reason" to proposal.reason)
    }

    @GetMapping("/{guildId}/ai-admin-roles")
    fun aiAdminRoles(
        @PathVariable guildId: Long,
    ) = customization.aiAdminRolePolicy(guildId)

    @PostMapping("/{guildId}/ai-admin-roles")
    fun replaceAiAdminRoles(
        @PathVariable guildId: Long,
        @RequestBody request: ReplaceAiAdminRolesRequest,
        httpRequest: HttpServletRequest,
    ): AiAdminRolePolicy {
        val actor = actorOf(httpRequest)
        return customization.replaceAiAdminRoles(
            guildId = guildId,
            roleIds = request.roleIds,
            // 권한/신원은 인증 주체에서 유도(body 불신).
            actorUserId = actor.userId,
            actorRoleIds = emptySet(),
            actorIsGuildAdmin = true,
        )
    }

    @GetMapping("/{guildId}/proposals/summary")
    fun proposalSummary(
        @PathVariable guildId: Long,
    ) = customization.proposalReviewSummary(guildId)

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
                "createdAt" to it.createdAt,
            )
        }

    @PostMapping("/{guildId}/{channelId}/prompt-preview")
    fun promptPreview(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: ChannelAiPromptPreviewRequest,
    ) = customization.promptPreview(guildId, channelId, request.userQuestion, request.ragContextText)

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
                        "createdAt" to it.createdAt,
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

// 보안(#1): 아래 DTO 들은 권한/신원 플래그(actorUserId/actorRoleIds/actorIsGuildAdmin/reviewer*)를
// **의도적으로 담지 않는다**. 권한은 서버측 인증 주체(DashboardActor)에서만 유도한다. 클라이언트가
// 이런 필드를 추가로 보내도 역직렬화에서 무시되므로(알 수 없는 필드 무시) 권한 부여에 절대 쓰이지 않는다.
data class ChannelAiWizardRequest(
    val name: String,
    val avatarUrl: String? = null,
    val job: String,
    val tone: String = "friendly",
    val answerLength: String = "balanced",
    val constitution: String? = null,
    // requireApproval 은 더 이상 받지 않는다 — 대시보드 wizard 는 항상 검토 큐로 보낸다(즉시 active 우회 차단).
)

data class RollbackChannelAiVersionRequest(
    val targetVersion: Int,
    val requireApproval: Boolean = false,
    val reason: String? = null,
)

data class ReviewChannelAiProposalRequest(
    val reason: String? = null,
)

data class ReplaceAiAdminRolesRequest(
    val roleIds: Set<Long> = emptySet(),
)

data class ChannelAiPromptPreviewRequest(
    val userQuestion: String,
    val ragContextText: String? = null,
)
