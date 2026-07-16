package com.discordassistant.central.channelai.adapter.inbound.web

import com.discordassistant.central.channelai.adapter.inbound.web.dto.ApproveChannelAiProposalResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ChannelAiHistoryResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ChannelAiPromptPreviewRequest
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ChannelAiWizardDraftRequest
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ChannelAiWizardDraftResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ChannelAiWizardRequest
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ChannelAiWizardResultResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.PendingProposalResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.RejectChannelAiProposalResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ReplaceAiAdminRolesRequest
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ReviewChannelAiProposalRequest
import com.discordassistant.central.channelai.adapter.inbound.web.dto.RollbackChannelAiVersionRequest
import com.discordassistant.central.channelai.application.AiAdminRolePolicy
import com.discordassistant.central.channelai.application.ChannelAiCustomizationService
import com.discordassistant.central.global.security.AiNetworkApiSecurityFilter
import com.discordassistant.central.global.security.DashboardActor
import com.discordassistant.central.shared.ContentSafety
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
 * (self-hosted 단일 운영자 모델). 컨트롤러는 [DashboardActor.from] 으로 신원(userId)만 추출해 넘기고,
 * per-guild AI-admin 역할 우회·즉시-active 우회 차단 같은 **권한 격상 규약은 application 의
 * `*AsTrustedDashboardAdmin` 오버로드 한 곳에서 강제**한다(컨트롤러에 권한 로직을 남기지 않는다).
 * 클라이언트가 보낸 어떤 플래그도 권한 부여에 쓰이지 않는다.
 */
@RestController
@RequestMapping("/api/ai-network/channel-ai")
class ChannelAiCustomizationController(
    private val customization: ChannelAiCustomizationService,
) {
    private fun actorOf(request: HttpServletRequest): DashboardActor = DashboardActor.from(request)

    @GetMapping("/wizard/options")
    fun wizardOptions() = customization.wizardOptions()

    @PostMapping("/wizard/draft")
    fun draft(
        @RequestBody request: ChannelAiWizardDraftRequest,
    ): ChannelAiWizardDraftResponse =
        ChannelAiWizardDraftResponse
            .from(
                customization.draftFromAnswers(
                    job = request.job,
                    tone = request.tone,
                    answerLength = request.answerLength,
                    customName = request.name,
                ),
            )

    @PostMapping("/{guildId}/{channelId}/wizard")
    fun createFromWizard(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: ChannelAiWizardRequest,
        httpRequest: HttpServletRequest,
    ): ChannelAiWizardResultResponse =
        ChannelAiWizardResultResponse
            .from(
                customization.createFromWizardAsTrustedDashboardAdmin(
                    guildId = guildId,
                    channelId = channelId,
                    // 권한/신원은 인증 주체에서 유도(body 불신). actor user id 는 audit 추적성용.
                    actorUserId = actorOf(httpRequest).userId,
                    name = request.name,
                    avatarUrl = request.avatarUrl,
                    job = request.job,
                    tone = request.tone,
                    answerLength = request.answerLength,
                    constitution = request.constitution,
                ),
            )

    @PostMapping("/{guildId}/{channelId}/rollback")
    fun rollback(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: RollbackChannelAiVersionRequest,
        httpRequest: HttpServletRequest,
    ): ChannelAiWizardResultResponse =
        ChannelAiWizardResultResponse
            .from(
                customization.rollbackToVersionAsTrustedDashboardAdmin(
                    guildId = guildId,
                    channelId = channelId,
                    targetVersion = request.targetVersion,
                    // 권한/신원은 인증 주체에서 유도(body 불신).
                    actorUserId = actorOf(httpRequest).userId,
                    requireApproval = request.requireApproval,
                    reason = request.reason,
                ),
            )

    @PostMapping("/proposals/{proposalId}/approve")
    fun approve(
        @PathVariable proposalId: Long,
        @RequestBody request: ReviewChannelAiProposalRequest,
        httpRequest: HttpServletRequest,
    ): ApproveChannelAiProposalResponse =
        ApproveChannelAiProposalResponse
            .from(
                customization.approveProposalAsTrustedDashboardAdmin(
                    proposalId = proposalId,
                    // 검토자 권한/신원은 인증 주체에서 유도(body 불신).
                    reviewerUserId = actorOf(httpRequest).userId,
                    reason = request.reason,
                ),
            )

    @PostMapping("/proposals/{proposalId}/reject")
    fun reject(
        @PathVariable proposalId: Long,
        @RequestBody request: ReviewChannelAiProposalRequest,
        httpRequest: HttpServletRequest,
    ): RejectChannelAiProposalResponse =
        RejectChannelAiProposalResponse
            .from(
                customization.rejectProposalAsTrustedDashboardAdmin(
                    proposalId = proposalId,
                    // 검토자 권한/신원은 인증 주체에서 유도(body 불신).
                    reviewerUserId = actorOf(httpRequest).userId,
                    reason = request.reason,
                ),
            )

    @GetMapping("/{guildId}/ai-admin-roles")
    fun aiAdminRoles(
        @PathVariable guildId: Long,
    ) = customization.aiAdminRolePolicy(guildId)

    @PostMapping("/{guildId}/ai-admin-roles")
    fun replaceAiAdminRoles(
        @PathVariable guildId: Long,
        @RequestBody request: ReplaceAiAdminRolesRequest,
        httpRequest: HttpServletRequest,
    ): AiAdminRolePolicy =
        customization.replaceAiAdminRolesAsTrustedDashboardAdmin(
            guildId = guildId,
            roleIds = request.roleIds,
            // 권한/신원은 인증 주체에서 유도(body 불신).
            actorUserId = actorOf(httpRequest).userId,
        )

    @GetMapping("/{guildId}/proposals/summary")
    fun proposalSummary(
        @PathVariable guildId: Long,
    ) = customization.proposalReviewSummary(guildId)

    @GetMapping("/{guildId}/proposals/pending")
    fun pending(
        @PathVariable guildId: Long,
    ): List<PendingProposalResponse> = customization.pendingProposals(guildId).map(PendingProposalResponse::from)

    @PostMapping("/{guildId}/{channelId}/prompt-preview")
    fun promptPreview(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: ChannelAiPromptPreviewRequest,
    ) = // 미리보기 응답에서 NEXA 가드레일 전문은 자리표시자로 가린다(영업·안전 비공개, F12 로도 전문 확인 불가).
        // 실제 LLM 실행 프롬프트(AskCommandHandler)는 마스킹하지 않은 전문을 그대로 사용한다.
        customization
            .promptPreview(guildId, channelId, request.userQuestion, request.ragContextText)
            .let { it.copy(systemPrompt = ContentSafety.maskGuardrail(it.systemPrompt)) }

    @GetMapping("/{guildId}/{channelId}/onboarding")
    fun onboarding(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
    ) = customization.channelOnboarding(guildId, channelId)

    @GetMapping("/{guildId}/{channelId}/history")
    fun history(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
    ): ChannelAiHistoryResponse = ChannelAiHistoryResponse.from(customization.channelHistory(guildId, channelId))
}
