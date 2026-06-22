package com.discordassistant.central.participation.application.model

import java.time.Clock
import java.time.Instant

/**
 * 모델·설정 변경 **human approval** 서비스(NEXA-P19-T020, application 레이어).
 *
 * 새 모델 지표·제한·artifact hash 를 사람이 **보고 승인/거절** 한다(deliverable T020). 승인은 곧 배포가 아니다 —
 * 이 서비스는 후보를 [ModelStatus.SHADOW]→[ModelStatus.APPROVED] 로 올리는 게이트일 뿐, **자동 배포·자동 LIVE
 * 승격을 하지 않는다**([ShadowModelRegistry] 가 LIVE 자격만 부여, 실제 LIVE 선택·rollout 은 별도 운영 경로).
 *
 * **acceptance(T020) — 승인 권한, 이중 확인, audit, rollback target 이 있다**:
 *  - **승인 권한**: [approve]/[reject] 는 [approverId] 를 받는다(누가 승인했는지 — 권한 주체는 어댑터 보안
 *    필터가 admin 으로 강제, [com.discordassistant.central.global.security.AiNetworkApiSecurityFilter]).
 *  - **이중 확인**: [approve] 는 [confirmationToken] 이 [ModelApprovalProposal.confirmationToken] 과 일치해야
 *    한다 — 한 번의 클릭으로 승인되지 않는다(오승인 방지). 불일치면 거부.
 *  - **audit**: 모든 승인/거절이 [ModelApprovalAuditPort] 에 [ApprovalAuditEntry] 로 기록된다(누가·언제·무엇을·왜).
 *  - **rollback target**: 승인 결과 [ApprovalDecisionResult.rollbackTargetModelId] 가 현재 LIVE 모델을 가리켜,
 *    승인 후 문제가 생기면 되돌릴 대상을 명시한다(ModelRollback 과 연결).
 *
 * 순수성 경계: application 레이어 — 포트·application 값 객체·표준 Clock 만. Spring/JPA/JDA 미참조.
 */
class ModelApprovalService(
    private val registry: ShadowModelRegistry,
    private val audit: ModelApprovalAuditPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 후보를 승인한다 — **이중 확인 토큰 검증** 후 SHADOW→APPROVED 로 올린다. 승인은 LIVE 자격을 줄 뿐 자동
     * 배포가 아니다. 모든 시도는 audit 에 기록된다(성공·실패 모두).
     *
     * @param proposal 사람이 본 제안(지표·제한·artifact hash·이중확인 토큰).
     * @param approverId 승인 주체(admin — 어댑터 보안이 강제).
     * @param confirmationToken 화면이 다시 입력받은 이중확인 토큰([proposal] 의 것과 일치해야 함).
     * @param currentLiveModelId 현재 LIVE 모델 id(없으면 null) — rollback target.
     */
    fun approve(
        proposal: ModelApprovalProposal,
        approverId: String,
        confirmationToken: String,
        currentLiveModelId: String?,
    ): ApprovalDecisionResult {
        require(approverId.isNotBlank()) { "approverId 는 비어 있을 수 없다(승인 권한 주체)" }
        if (confirmationToken != proposal.confirmationToken) {
            recordAudit(proposal.modelId, approverId, ApprovalAction.APPROVE_REJECTED_DOUBLE_CONFIRM, null)
            throw ModelApprovalException(
                "이중 확인 토큰 불일치 — 승인 거부(modelId=${proposal.modelId}). 한 번의 클릭으로 승인되지 않는다.",
            )
        }
        val candidate = registry.transition(proposal.modelId, ModelStatus.APPROVED)
        recordAudit(proposal.modelId, approverId, ApprovalAction.APPROVED, currentLiveModelId)
        return ApprovalDecisionResult(
            modelId = candidate.modelId,
            newStatus = candidate.status,
            // 승인은 LIVE 가 아니다 — 자동 배포 없음을 결과에 명시(대시보드 표시).
            autoDeployed = false,
            rollbackTargetModelId = currentLiveModelId,
            decidedAt = Instant.now(clock),
            decidedBy = approverId,
        )
    }

    /**
     * 후보를 거절한다(SHADOW→REJECTED 또는 REGISTERED→REJECTED). audit 에 기록한다. 이중 확인 불필요(안전
     * 방향 — 거절은 LIVE 자격을 박탈할 뿐).
     */
    fun reject(
        modelId: String,
        approverId: String,
        reason: String,
    ): ApprovalDecisionResult {
        require(approverId.isNotBlank()) { "approverId 는 비어 있을 수 없다(승인 권한 주체)" }
        require(reason.isNotBlank()) { "거절에는 사유가 필요하다(audit)" }
        val candidate = registry.transition(modelId, ModelStatus.REJECTED)
        recordAudit(modelId, approverId, ApprovalAction.REJECTED, null, reason)
        return ApprovalDecisionResult(
            modelId = candidate.modelId,
            newStatus = candidate.status,
            autoDeployed = false,
            rollbackTargetModelId = null,
            decidedAt = Instant.now(clock),
            decidedBy = approverId,
        )
    }

    private fun recordAudit(
        modelId: String,
        approverId: String,
        action: ApprovalAction,
        rollbackTargetModelId: String?,
        reason: String? = null,
    ) {
        audit.record(
            ApprovalAuditEntry(
                modelId = modelId,
                approverId = approverId,
                action = action,
                rollbackTargetModelId = rollbackTargetModelId,
                reason = reason,
                at = Instant.now(clock),
            ),
        )
    }
}

/**
 * 사람이 검토하는 모델 승인 제안(application 값 객체·불변). 지표·제한·artifact hash·이중확인 토큰을 담는다 —
 * 화면이 그대로 보여주고 [confirmationToken] 으로 이중 확인을 받는다.
 */
data class ModelApprovalProposal(
    val modelId: String,
    /** artifact sha256(무결성 — 사람이 확인). */
    val artifactSha256: String,
    /** 모델 지표(balanced accuracy·FIR/MIR·ECE 등). 사람이 보고 판단. */
    val metrics: Map<String, Double>,
    /** 운영 제한(예: max_speak_share·daily_cap). 사람이 보고 판단. */
    val limits: Map<String, Double>,
    /** 이중 확인 토큰 — 화면이 다시 입력받아 [ModelApprovalService.approve] 에 넘긴다(오승인 방지). */
    val confirmationToken: String,
) {
    init {
        require(modelId.isNotBlank()) { "modelId 는 비어 있을 수 없다" }
        require(artifactSha256.isNotBlank()) { "artifactSha256 은 비어 있을 수 없다" }
        require(confirmationToken.isNotBlank()) { "confirmationToken 은 비어 있을 수 없다(이중 확인)" }
    }
}

/** 승인/거절 결과(application 값 객체). 자동 배포 여부·rollback target 을 명시한다(acceptance T020). */
data class ApprovalDecisionResult(
    val modelId: String,
    val newStatus: ModelStatus,
    /** 항상 false — 승인은 자동 배포가 아니다(LIVE 선택·rollout 은 별도 운영 경로). */
    val autoDeployed: Boolean,
    /** 승인 후 문제 시 되돌릴 대상(현재 LIVE 모델 id). 없으면 null. */
    val rollbackTargetModelId: String?,
    val decidedAt: Instant,
    val decidedBy: String,
)

/** 승인 audit 기록 단위(application 값 객체). 누가·언제·무엇을·왜. */
data class ApprovalAuditEntry(
    val modelId: String,
    val approverId: String,
    val action: ApprovalAction,
    val rollbackTargetModelId: String?,
    val reason: String?,
    val at: Instant,
)

/** 승인 audit action 코드(닫힌 enum — 감사·재현). */
enum class ApprovalAction {
    /** 승인됨(SHADOW→APPROVED). LIVE 자격 부여(자동 배포 아님). */
    APPROVED,

    /** 거절됨(→REJECTED). */
    REJECTED,

    /** 이중 확인 토큰 불일치로 승인이 거부됨(오승인 방지 발동). */
    APPROVE_REJECTED_DOUBLE_CONFIRM,
}

/** 모델 승인 audit 기록 포트(application 경계 — 구현은 adapter). */
fun interface ModelApprovalAuditPort {
    /** 한 승인/거절 audit 항목을 기록한다(부작용 — 영속). */
    fun record(entry: ApprovalAuditEntry)
}

/** 모델 승인 audit 조회 포트(read-only 대시보드용 — 구현은 adapter). */
fun interface ModelApprovalAuditQueryPort {
    /** 한 모델의 audit 이력을 최신순으로 돌려준다(집계 — 원문 미포함). */
    fun historyFor(modelId: String): List<ApprovalAuditEntry>
}

/** 모델 승인 불변식 위반(NEXA-P19-T020). 이중 확인 실패 등에서 던진다(fail-closed). */
class ModelApprovalException(
    message: String,
) : RuntimeException(message)
