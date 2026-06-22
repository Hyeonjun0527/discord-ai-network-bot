package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.participation.application.model.ApprovalAuditEntry
import com.discordassistant.central.participation.application.model.ModelApprovalAuditQueryPort
import com.discordassistant.central.participation.application.model.ModelApprovalProposal
import com.discordassistant.central.participation.application.model.ModelApprovalService
import com.discordassistant.central.participation.application.model.ShadowModelCandidate
import com.discordassistant.central.participation.application.model.ShadowModelRegistry
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 모델·설정 human approval API(NEXA-P19-T020). 관리자가 새 모델 후보의 지표·제한·artifact hash 를 보고
 * 승인/거절한다.
 *
 * **인증·승인 권한**: 경로(`/api/ai-network/model-approval` 하위)는
 * [com.discordassistant.central.global.security.AiNetworkApiSecurityFilter] 가 보호한다 — GET 은 admin read,
 * POST(approve/reject)는 UNSAFE 메서드라 OAuth(허용목록) 또는 admin-token 없이는 403. 승인 권한은 admin 주체뿐이다.
 *
 * **acceptance(T020) — 승인 권한·이중 확인·audit·rollback target**:
 *  - 승인 권한: 경로 보안(admin) + [approverId] 기록.
 *  - 이중 확인: [ApprovalRequest.confirmationToken] 이 후보 제안 토큰과 일치해야 승인된다(service 가 강제).
 *  - audit: 모든 승인/거절이 영속되고 [auditHistory] 로 조회된다.
 *  - rollback target: 승인 응답에 현재 LIVE 모델 id(되돌릴 대상)가 담긴다.
 *
 * **자동 배포 금지**: 승인은 SHADOW→APPROVED(LIVE 자격) 게이트일 뿐, 실제 LIVE 선택·rollout 은 별도 운영 경로다
 * (응답 [autoDeployed]=false 로 명시).
 */
@RestController
@RequestMapping("/api/ai-network/model-approval")
class ModelApprovalController(
    private val registry: ShadowModelRegistry,
    private val approval: ModelApprovalService,
    private val auditQuery: ModelApprovalAuditQueryPort,
) {
    /** 승인 대기/이력 후보 목록(집계 — 신원·hash·상태만). */
    @GetMapping("/candidates")
    fun candidates(): List<ModelCandidateDto> = registry.listAll().map { it.toDto() }

    /** 한 모델의 승인 audit 이력(최신순). 누가·언제·무엇을·왜. */
    @GetMapping("/{modelId}/audit")
    fun auditHistory(
        @PathVariable modelId: String,
    ): List<ApprovalAuditDto> = auditQuery.historyFor(modelId).map { it.toDto() }

    /**
     * 후보 승인(SHADOW→APPROVED) — **이중 확인 토큰** 검증 후. 자동 배포 아님. 승인 응답에 rollback target 명시.
     */
    @PostMapping("/{modelId}/approve")
    fun approve(
        @PathVariable modelId: String,
        @RequestBody request: ApprovalRequest,
    ): ApprovalDecisionDto {
        val proposal =
            ModelApprovalProposal(
                modelId = modelId,
                artifactSha256 = request.artifactSha256,
                metrics = request.metrics,
                limits = request.limits,
                confirmationToken = request.expectedConfirmationToken,
            )
        val result =
            approval.approve(
                proposal = proposal,
                approverId = currentApproverId(),
                confirmationToken = request.confirmationToken,
                currentLiveModelId = request.currentLiveModelId,
            )
        return ApprovalDecisionDto(
            modelId = result.modelId,
            newStatus = result.newStatus.name,
            autoDeployed = result.autoDeployed,
            rollbackTargetModelId = result.rollbackTargetModelId,
            decidedAt = result.decidedAt,
            decidedBy = result.decidedBy,
        )
    }

    /** 후보 거절(→REJECTED). 사유 필수(audit). */
    @PostMapping("/{modelId}/reject")
    fun reject(
        @PathVariable modelId: String,
        @RequestBody request: RejectRequest,
    ): ApprovalDecisionDto {
        val result = approval.reject(modelId = modelId, approverId = currentApproverId(), reason = request.reason)
        return ApprovalDecisionDto(
            modelId = result.modelId,
            newStatus = result.newStatus.name,
            autoDeployed = result.autoDeployed,
            rollbackTargetModelId = result.rollbackTargetModelId,
            decidedAt = result.decidedAt,
            decidedBy = result.decidedBy,
        )
    }

    /** 승인 주체 — OAuth 사용자 id 또는 admin-token system 주체. 경로 보안이 admin 을 이미 강제한다. */
    private fun currentApproverId(): String {
        val name = SecurityContextHolder.getContext().authentication?.name
        return name?.takeIf { it.isNotBlank() } ?: "system"
    }

    private fun ShadowModelCandidate.toDto(): ModelCandidateDto =
        ModelCandidateDto(
            modelId = modelId,
            artifactSha256 = artifactSha256,
            modelVersion = modelVersion,
            featureSchemaVersion = featureSchemaVersion,
            calibrationVersion = calibrationVersion,
            status = status.name,
            selectableForLive = isSelectableForLive,
            registeredAt = registeredAt,
        )

    private fun ApprovalAuditEntry.toDto(): ApprovalAuditDto =
        ApprovalAuditDto(
            modelId = modelId,
            approverId = approverId,
            action = action.name,
            rollbackTargetModelId = rollbackTargetModelId,
            reason = reason,
            at = at,
        )
}

/** 승인 요청 본문. 이중 확인 토큰을 [confirmationToken] 으로 다시 받는다(오승인 방지). */
data class ApprovalRequest(
    val artifactSha256: String,
    val metrics: Map<String, Double> = emptyMap(),
    val limits: Map<String, Double> = emptyMap(),
    /** 제안에 표시된 기대 토큰. */
    val expectedConfirmationToken: String,
    /** 사용자가 다시 입력한 확인 토큰(둘이 같아야 승인). */
    val confirmationToken: String,
    /** 현재 LIVE 모델 id(rollback target). 없으면 null. */
    val currentLiveModelId: String? = null,
)

/** 거절 요청 본문. 사유 필수. */
data class RejectRequest(
    val reason: String,
)

/** 모델 후보 응답 DTO(신원·hash·상태만 — 원문 비포함). */
data class ModelCandidateDto(
    val modelId: String,
    val artifactSha256: String,
    val modelVersion: String,
    val featureSchemaVersion: Int,
    val calibrationVersion: String,
    val status: String,
    /** APPROVED 라서 LIVE 선택 자격이 있는가. */
    val selectableForLive: Boolean,
    val registeredAt: Instant,
)

/** 승인/거절 결과 DTO. 자동 배포 여부·rollback target 명시(acceptance T020). */
data class ApprovalDecisionDto(
    val modelId: String,
    val newStatus: String,
    /** 항상 false — 승인은 자동 배포가 아니다. */
    val autoDeployed: Boolean,
    val rollbackTargetModelId: String?,
    val decidedAt: Instant,
    val decidedBy: String,
)

/** 승인 audit 이력 DTO(누가·언제·무엇을·왜). */
data class ApprovalAuditDto(
    val modelId: String,
    val approverId: String,
    val action: String,
    val rollbackTargetModelId: String?,
    val reason: String?,
    val at: Instant,
)
