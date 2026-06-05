package com.discordassistant.central.multiresponse.adapter.inbound.web.dto

import com.discordassistant.central.ainetwork.application.DashboardAudience
import com.discordassistant.central.multiresponse.application.CandidateAdoptionResult
import com.discordassistant.central.multiresponse.application.CandidateAnswerView
import com.discordassistant.central.multiresponse.application.MultiResponseCompletion
import com.discordassistant.central.multiresponse.application.MultiResponseDailyStats
import com.discordassistant.central.multiresponse.application.MultiResponseDecisionSummary
import com.discordassistant.central.multiresponse.application.MultiResponseFanoutRecommendation
import com.discordassistant.central.multiresponse.application.MultiResponseOperationsSummary
import com.discordassistant.central.multiresponse.application.MultiResponsePolicyView
import com.discordassistant.central.multiresponse.application.MultiResponseRunDetail
import com.discordassistant.central.multiresponse.application.MultiResponseRunView
import com.discordassistant.central.multiresponse.application.ProviderFanoutLoadSummary
import com.discordassistant.central.multiresponse.application.PseudoStreamPlan
import com.discordassistant.central.multiresponse.application.SynthesisResultView

// 다중응답 인바운드 어댑터(컨트롤러)가 application View/Summary 를 와이어 응답으로 매핑하는 DTO 모음.
//
// - 컨트롤러는 "파싱 → application 호출 → DTO.from()" 3단계만 수행하고 집계/비즈니스 로직은 갖지 않는다.
// - toMap() 은 분해 이전 인라인 mapOf 의 키 이름·값·순서·null 처리·중첩 구조를 1바이트도 바꾸지 않고 그대로 재현한다.
// - audience 기반 필드 마스킹(보안)·providerLabel 프레젠테이션은 from(...) 팩토리 안으로만 흡수한다.

/** providerLabel 프레젠테이션 로직(컨트롤러에서 이동, 의미 변경 없음). */
internal fun providerLabel(
    providerUserId: Long?,
    index: Int,
): String = providerUserId?.let { "Provider ${kotlin.math.abs(it.hashCode()).toString(36).take(6)}" } ?: "Provider ${index + 1}"

/** POST /{guildId}/policy */
data class SavePolicyResponse(
    val id: Long,
    val mode: String,
    val maxCandidates: Int,
    val synthesisEnabled: Boolean,
    val disabledReason: String?,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "mode" to mode,
            "maxCandidates" to maxCandidates,
            "synthesisEnabled" to synthesisEnabled,
            "disabledReason" to disabledReason,
        )

    companion object {
        fun from(policy: MultiResponsePolicyView): SavePolicyResponse =
            SavePolicyResponse(
                id = policy.id,
                mode = policy.mode,
                maxCandidates = policy.maxCandidates,
                synthesisEnabled = policy.synthesisEnabled,
                disabledReason = policy.disabledReason,
            )
    }
}

/** POST /{guildId}/runs */
data class StartRunResponse(
    val id: Long,
    val requestId: String,
    val status: String,
    val candidateCount: Int,
    val ragContextStatus: String?,
    val ragContextChars: Int,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "requestId" to requestId,
            "status" to status,
            "candidateCount" to candidateCount,
            "ragContextStatus" to ragContextStatus,
            "ragContextChars" to ragContextChars,
        )

    companion object {
        fun from(run: MultiResponseRunView): StartRunResponse =
            StartRunResponse(
                id = run.id,
                requestId = run.requestId,
                status = run.status,
                candidateCount = run.candidateCount,
                ragContextStatus = run.ragContextStatus,
                ragContextChars = run.ragContextChars,
            )
    }
}

/** POST /runs/{runId}/candidates/{candidateId} */
data class RecordCandidateResponse(
    val id: Long,
    val status: String,
    val qualityScore: Int?,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "status" to status, "qualityScore" to qualityScore)

    companion object {
        fun from(candidate: CandidateAnswerView): RecordCandidateResponse =
            RecordCandidateResponse(id = candidate.id, status = candidate.status, qualityScore = candidate.qualityScore)
    }
}

/** POST /runs/{runId}/synthesis */
data class SynthesizeResponse(
    val id: Long,
    val status: String,
    val answerRef: String?,
    val strategy: String,
    val qualitySummary: String?,
    val safetySummary: String?,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "status" to status,
            "answerRef" to answerRef,
            "strategy" to strategy,
            "qualitySummary" to qualitySummary,
            "safetySummary" to safetySummary,
        )

    companion object {
        fun from(synthesis: SynthesisResultView): SynthesizeResponse =
            SynthesizeResponse(
                id = synthesis.id,
                status = synthesis.status,
                answerRef = synthesis.answerRef,
                strategy = synthesis.strategy,
                qualitySummary = synthesis.qualitySummary,
                safetySummary = synthesis.safetySummary,
            )
    }
}

/** POST /runs/{runId}/candidates/{candidateId}/adopt */
data class AdoptCandidateResponse(
    val runId: Long,
    val status: String,
    val selectedCandidateId: Long?,
    val candidateQualityScore: Int?,
    val synthesisId: Long,
    val feedbackId: Long?,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "runId" to runId,
            "status" to status,
            "selectedCandidateId" to selectedCandidateId,
            "candidateQualityScore" to candidateQualityScore,
            "synthesisId" to synthesisId,
            "feedbackId" to feedbackId,
        )

    companion object {
        fun from(adoption: CandidateAdoptionResult): AdoptCandidateResponse =
            AdoptCandidateResponse(
                runId = adoption.run.id,
                status = adoption.run.status,
                selectedCandidateId = adoption.run.selectedCandidateId,
                candidateQualityScore = adoption.candidate.qualityScore,
                synthesisId = adoption.synthesis.id,
                feedbackId = adoption.feedbackId,
            )
    }
}

/** POST /runs/{runId}/complete-best */
data class CompleteBestResponse(
    val id: Long,
    val status: String,
    val selectedCandidateId: Long?,
    val synthesisId: Long?,
    val answerRef: String?,
    val fallbackReason: String?,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "status" to status,
            "selectedCandidateId" to selectedCandidateId,
            "synthesisId" to synthesisId,
            "answerRef" to answerRef,
            "fallbackReason" to fallbackReason,
        )

    companion object {
        fun from(completion: MultiResponseCompletion): CompleteBestResponse =
            CompleteBestResponse(
                id = completion.run.id,
                status = completion.run.status,
                selectedCandidateId = completion.run.selectedCandidateId,
                synthesisId = completion.synthesis?.id,
                answerRef = completion.synthesis?.answerRef,
                fallbackReason = completion.fallbackReason,
            )
    }
}

/** POST /runs/{runId}/fail */
data class FailRunResponse(
    val id: Long,
    val status: String,
    val failureReason: String?,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "status" to status, "failureReason" to failureReason)

    companion object {
        fun from(run: MultiResponseRunView): FailRunResponse =
            FailRunResponse(id = run.id, status = run.status, failureReason = run.failureReason)
    }
}

/** POST /pseudo-stream-plan */
data class PseudoStreamPlanResponse(
    val plan: PseudoStreamPlan,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "finalLength" to plan.finalLength,
            "truncated" to plan.truncated,
            "editIntervalMs" to plan.editIntervalMs,
            "snapshots" to plan.snapshots,
            "warning" to plan.warning,
        )

    companion object {
        fun from(plan: PseudoStreamPlan): PseudoStreamPlanResponse = PseudoStreamPlanResponse(plan)
    }
}

/** GET /{guildId}/runs (목록 1건) */
data class RecentRunResponse(
    val run: MultiResponseRunView,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to run.id,
            "requestId" to run.requestId,
            "channelId" to run.channelId,
            "status" to run.status,
            "candidateCount" to run.candidateCount,
            "startedAt" to run.startedAt.toString(),
            "finishedAt" to run.finishedAt?.toString(),
        )

    companion object {
        fun from(run: MultiResponseRunView): RecentRunResponse = RecentRunResponse(run)
    }
}

/**
 * GET /runs/{runId}
 *
 * audience 기반 redaction(보안)을 흡수: admin(canSeeProviderIdentity)만 providerUserId/answerRef 를 노출하고,
 * 비-admin 은 providerUserId=null·answerRef 키 자체를 생략한다(분해 이전과 동일한 키 존재 여부).
 */
class RunDetailResponse private constructor(
    private val detail: MultiResponseRunDetail,
    private val canSeeProviderIdentity: Boolean,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to detail.run.id,
            "requestId" to detail.run.requestId,
            "channelId" to detail.run.channelId,
            "status" to detail.run.status,
            "candidateCount" to detail.run.candidateCount,
            "ragContextStatus" to detail.run.ragContextStatus,
            "ragContextSourceIds" to detail.run.ragContextSourceIds,
            "ragContextChars" to detail.run.ragContextChars,
            "policy" to
                detail.policy?.let {
                    mapOf(
                        "mode" to it.mode,
                        "maxCandidates" to it.maxCandidates,
                        "synthesisEnabled" to it.synthesisEnabled,
                        "disabledReason" to it.disabledReason,
                    )
                },
            "candidates" to
                detail.candidates.mapIndexed { index, candidate ->
                    mapOf(
                        "id" to candidate.id,
                        "providerUserId" to if (canSeeProviderIdentity) candidate.providerUserId else null,
                        "providerLabel" to providerLabel(candidate.providerUserId, index),
                        "modelName" to candidate.modelName,
                        "status" to candidate.status,
                        "latencyMs" to candidate.latencyMs,
                        "qualityScore" to candidate.qualityScore,
                        "safetyFlags" to candidate.safetyFlags,
                    ) + if (canSeeProviderIdentity) mapOf("answerRef" to candidate.answerRef) else emptyMap()
                },
            "synthesis" to
                detail.synthesis?.let {
                    mapOf(
                        "id" to it.id,
                        "status" to it.status,
                        "strategy" to it.strategy,
                        "selectedCandidateIds" to it.selectedCandidateIds,
                        "qualitySummary" to it.qualitySummary,
                        "safetySummary" to it.safetySummary,
                    ) + if (canSeeProviderIdentity) mapOf("answerRef" to it.answerRef) else emptyMap()
                },
            "qualitySummary" to detail.qualitySummary,
            "safetySummary" to detail.safetySummary,
        )

    companion object {
        fun from(
            detail: MultiResponseRunDetail,
            audience: String,
        ): RunDetailResponse = RunDetailResponse(detail, audience.equals("admin", ignoreCase = true))
    }
}

/** GET /{guildId}/decision-summary */
data class DecisionSummaryResponse(
    val summary: MultiResponseDecisionSummary,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "guildId" to summary.guildId,
            "channelId" to summary.channelId,
            "recentRunCount" to summary.recentRunCount,
            "completedRunCount" to summary.completedRunCount,
            "fallbackRunCount" to summary.fallbackRunCount,
            "totalCandidateCount" to summary.totalCandidateCount,
            "acceptedCandidateCount" to summary.acceptedCandidateCount,
            "rejectedCandidateCount" to summary.rejectedCandidateCount,
            "timeoutCandidateCount" to summary.timeoutCandidateCount,
            "averageQualityScore" to summary.averageQualityScore,
            "adoptionRate" to summary.adoptionRate,
            "statusCounts" to summary.statusCounts,
            "riskCodes" to summary.riskCodes,
            "nextActions" to summary.nextActions,
            "recentDecisions" to summary.recentDecisions,
        )

    companion object {
        fun from(summary: MultiResponseDecisionSummary): DecisionSummaryResponse = DecisionSummaryResponse(summary)
    }
}

/** GET /{guildId}/stats */
data class StatsResponse(
    val stats: MultiResponseDailyStats,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "guildId" to stats.guildId,
            "recentRunCount" to stats.recentRunCount,
            "completedRunCount" to stats.completedRunCount,
            "fallbackRunCount" to stats.fallbackRunCount,
            "timeoutCandidateCount" to stats.timeoutCandidateCount,
            "averageActualFanout" to stats.averageActualFanout,
        )

    companion object {
        fun from(stats: MultiResponseDailyStats): StatsResponse = StatsResponse(stats)
    }
}

/**
 * GET /{guildId}/recommendation
 *
 * audience 기반 redaction(보안)을 흡수: canSeeProviderIdentity 만 providerUserId 노출,
 * overloadRisk 는 `DashboardAudience.PUBLIC.risk(...)` 로 강제 다운그레이드(의미 변경 없음).
 */
class FanoutRecommendationResponse private constructor(
    private val recommendation: MultiResponseFanoutRecommendation,
    private val audience: DashboardAudience,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "guildId" to recommendation.guildId,
            "channelId" to recommendation.channelId,
            "policySource" to recommendation.policySource,
            "policyMode" to recommendation.policyMode,
            "requestedCandidates" to recommendation.requestedCandidates,
            "maxSafeCandidates" to recommendation.maxSafeCandidates,
            "recommendedCandidateCount" to recommendation.recommendedCandidateCount,
            "fanoutAllowed" to recommendation.fanoutAllowed,
            "status" to recommendation.status,
            "reasons" to recommendation.reasons,
            "providers" to
                recommendation.providers.mapIndexed { index, provider ->
                    mapOf(
                        "providerUserId" to if (audience.canSeeProviderIdentity) provider.providerUserId else null,
                        "providerLabel" to providerLabel(provider.providerUserId, index),
                        "modelName" to provider.modelName,
                        "qualityTier" to provider.qualityTier,
                        "overloadRisk" to DashboardAudience.PUBLIC.risk(provider.overloadRisk),
                    )
                },
        )

    companion object {
        fun from(
            recommendation: MultiResponseFanoutRecommendation,
            audience: DashboardAudience,
        ): FanoutRecommendationResponse = FanoutRecommendationResponse(recommendation, audience)
    }
}

/** GET /{guildId}/operations-summary (래퍼: {"summary": ...}) */
data class OperationsSummaryResponse(
    val summary: MultiResponseOperationsDashboardResponse,
) {
    fun toMap(): Map<String, Any?> = mapOf("summary" to summary)

    companion object {
        fun from(
            summary: MultiResponseOperationsSummary,
            audience: DashboardAudience,
        ): OperationsSummaryResponse = OperationsSummaryResponse(MultiResponseOperationsDashboardResponse.from(summary, audience))
    }
}

data class MultiResponseOperationsDashboardResponse(
    val guildId: Long,
    val channelId: Long?,
    val status: String,
    val safeToEnableAdvanced: Boolean,
    val recentRunCount: Int,
    val completedRunCount: Int,
    val fallbackRunCount: Int,
    val averageActualFanout: Double,
    val acceptedCandidateCount: Int,
    val timeoutCandidateCount: Int,
    val rejectedCandidateCount: Int,
    val highLoadProviderCount: Int,
    val criticalLoadProviderCount: Int,
    val ragFallbackRunCount: Int,
    val blockedSensitiveRunCount: Int,
    val noProviderRunCount: Int,
    val providerProtectionBlockedCount: Int,
    val recentProviderProtectionReasons: List<String>,
    val riskCodes: List<String>,
    val nextActions: List<String>,
    val providerLoads: List<ProviderFanoutLoadDashboardResponse>,
    val decisionSummary: MultiResponseDecisionSummary,
) {
    companion object {
        fun from(
            summary: MultiResponseOperationsSummary,
            audience: DashboardAudience,
        ): MultiResponseOperationsDashboardResponse =
            MultiResponseOperationsDashboardResponse(
                guildId = summary.guildId,
                channelId = summary.channelId,
                status = summary.status,
                safeToEnableAdvanced = summary.safeToEnableAdvanced,
                recentRunCount = summary.recentRunCount,
                completedRunCount = summary.completedRunCount,
                fallbackRunCount = summary.fallbackRunCount,
                averageActualFanout = summary.averageActualFanout,
                acceptedCandidateCount = summary.acceptedCandidateCount,
                timeoutCandidateCount = summary.timeoutCandidateCount,
                rejectedCandidateCount = summary.rejectedCandidateCount,
                highLoadProviderCount = summary.highLoadProviderCount,
                criticalLoadProviderCount = summary.criticalLoadProviderCount,
                ragFallbackRunCount = summary.ragFallbackRunCount,
                blockedSensitiveRunCount = summary.blockedSensitiveRunCount,
                noProviderRunCount = summary.noProviderRunCount,
                providerProtectionBlockedCount = summary.providerProtectionBlockedCount,
                recentProviderProtectionReasons = summary.recentProviderProtectionReasons,
                riskCodes = summary.riskCodes,
                nextActions = summary.nextActions,
                providerLoads =
                    summary.providerLoads.mapIndexed { index, load ->
                        ProviderFanoutLoadDashboardResponse.from(load, index, audience)
                    },
                decisionSummary = summary.decisionSummary,
            )
    }
}

data class ProviderFanoutLoadDashboardResponse(
    val guildId: Long,
    val providerUserId: Long?,
    val providerLabel: String,
    val candidateCount: Int,
    val completedCount: Int,
    val timeoutCount: Int,
    val failedCount: Int,
    val averageLatencyMs: Double,
    val averageQualityScore: Double,
    val loadRisk: String,
    val runIds: List<Long>,
) {
    companion object {
        fun from(
            load: ProviderFanoutLoadSummary,
            index: Int,
            audience: DashboardAudience,
        ): ProviderFanoutLoadDashboardResponse =
            ProviderFanoutLoadDashboardResponse(
                guildId = load.guildId,
                providerUserId = if (audience.canSeeProviderIdentity) load.providerUserId else null,
                providerLabel =
                    if (audience.canSeeProviderIdentity) {
                        "provider:${load.providerUserId}"
                    } else {
                        val label =
                            kotlin.math
                                .abs(load.providerUserId.hashCode())
                                .toString(36)
                                .take(6)
                                .ifBlank { (index + 1).toString() }
                        "Provider $label"
                    },
                candidateCount = load.candidateCount,
                completedCount = load.completedCount,
                timeoutCount = load.timeoutCount,
                failedCount = load.failedCount,
                averageLatencyMs = load.averageLatencyMs,
                averageQualityScore = load.averageQualityScore,
                loadRisk = if (audience.canSeeProviderCapacity) load.loadRisk else DashboardAudience.PUBLIC.risk(load.loadRisk),
                runIds = load.runIds,
            )
    }
}
