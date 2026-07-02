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
import com.discordassistant.central.multiresponse.application.MultiResponseRecommendedProvider
import com.discordassistant.central.multiresponse.application.MultiResponseRunDetail
import com.discordassistant.central.multiresponse.application.MultiResponseRunView
import com.discordassistant.central.multiresponse.application.ProviderFanoutLoadSummary
import com.discordassistant.central.multiresponse.application.PseudoStreamPlan
import com.discordassistant.central.multiresponse.application.PseudoStreamSnapshot
import com.discordassistant.central.multiresponse.application.SynthesisResultView
import com.fasterxml.jackson.annotation.JsonInclude

// 다중응답 인바운드 어댑터가 application View/Summary 를 와이어 응답으로 매핑하는 DTO 모음.
// 기존 JSON field 이름은 유지하되 Map 기반 응답 조립을 제거한다.

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
    companion object {
        fun from(run: MultiResponseRunView): FailRunResponse =
            FailRunResponse(id = run.id, status = run.status, failureReason = run.failureReason)
    }
}

/** POST /pseudo-stream-plan */
data class PseudoStreamPlanResponse(
    val finalLength: Int,
    val truncated: Boolean,
    val editIntervalMs: Int,
    val snapshots: List<PseudoStreamSnapshot>,
    val warning: String?,
) {
    companion object {
        fun from(plan: PseudoStreamPlan): PseudoStreamPlanResponse =
            PseudoStreamPlanResponse(
                finalLength = plan.finalLength,
                truncated = plan.truncated,
                editIntervalMs = plan.editIntervalMs,
                snapshots = plan.snapshots,
                warning = plan.warning,
            )
    }
}

/** GET /{guildId}/runs (목록 1건) */
data class RecentRunResponse(
    val id: Long,
    val requestId: String,
    val channelId: Long,
    val status: String,
    val candidateCount: Int,
    val startedAt: String,
    val finishedAt: String?,
) {
    companion object {
        fun from(run: MultiResponseRunView): RecentRunResponse =
            RecentRunResponse(
                id = run.id,
                requestId = run.requestId,
                channelId = run.channelId,
                status = run.status,
                candidateCount = run.candidateCount,
                startedAt = run.startedAt.toString(),
                finishedAt = run.finishedAt?.toString(),
            )
    }
}

data class RunDetailPolicyResponse(
    val mode: String,
    val maxCandidates: Int,
    val synthesisEnabled: Boolean,
    val disabledReason: String?,
) {
    companion object {
        fun from(policy: MultiResponsePolicyView): RunDetailPolicyResponse =
            RunDetailPolicyResponse(
                mode = policy.mode,
                maxCandidates = policy.maxCandidates,
                synthesisEnabled = policy.synthesisEnabled,
                disabledReason = policy.disabledReason,
            )
    }
}

data class RunDetailCandidateResponse(
    val id: Long,
    val providerUserId: Long?,
    val providerLabel: String,
    val modelName: String?,
    val status: String,
    val latencyMs: Int?,
    val qualityScore: Int?,
    val safetyFlags: String?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val answerRef: String?,
) {
    companion object {
        fun from(
            candidate: CandidateAnswerView,
            index: Int,
            canSeeProviderIdentity: Boolean,
        ): RunDetailCandidateResponse =
            RunDetailCandidateResponse(
                id = candidate.id,
                providerUserId = if (canSeeProviderIdentity) candidate.providerUserId else null,
                providerLabel = providerLabel(candidate.providerUserId, index),
                modelName = candidate.modelName,
                status = candidate.status,
                latencyMs = candidate.latencyMs,
                qualityScore = candidate.qualityScore,
                safetyFlags = candidate.safetyFlags,
                answerRef = if (canSeeProviderIdentity) candidate.answerRef else null,
            )
    }
}

data class RunDetailSynthesisResponse(
    val id: Long,
    val status: String,
    val strategy: String,
    val selectedCandidateIds: String?,
    val qualitySummary: String?,
    val safetySummary: String?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val answerRef: String?,
) {
    companion object {
        fun from(
            synthesis: SynthesisResultView,
            canSeeProviderIdentity: Boolean,
        ): RunDetailSynthesisResponse =
            RunDetailSynthesisResponse(
                id = synthesis.id,
                status = synthesis.status,
                strategy = synthesis.strategy,
                selectedCandidateIds = synthesis.selectedCandidateIds,
                qualitySummary = synthesis.qualitySummary,
                safetySummary = synthesis.safetySummary,
                answerRef = if (canSeeProviderIdentity) synthesis.answerRef else null,
            )
    }
}

/**
 * GET /runs/{runId}
 *
 * audience 기반 redaction(보안): admin(canSeeProviderIdentity)만 answerRef 를 직렬화한다.
 */
data class RunDetailResponse(
    val id: Long,
    val requestId: String,
    val channelId: Long,
    val status: String,
    val candidateCount: Int,
    val ragContextStatus: String?,
    val ragContextSourceIds: String?,
    val ragContextChars: Int,
    val policy: RunDetailPolicyResponse?,
    val candidates: List<RunDetailCandidateResponse>,
    val synthesis: RunDetailSynthesisResponse?,
    val qualitySummary: String,
    val safetySummary: String,
) {
    companion object {
        fun from(
            detail: MultiResponseRunDetail,
            audience: String,
        ): RunDetailResponse {
            val canSeeProviderIdentity = audience.equals("admin", ignoreCase = true)
            return RunDetailResponse(
                id = detail.run.id,
                requestId = detail.run.requestId,
                channelId = detail.run.channelId,
                status = detail.run.status,
                candidateCount = detail.run.candidateCount,
                ragContextStatus = detail.run.ragContextStatus,
                ragContextSourceIds = detail.run.ragContextSourceIds,
                ragContextChars = detail.run.ragContextChars,
                policy = detail.policy?.let(RunDetailPolicyResponse::from),
                candidates =
                    detail.candidates.mapIndexed { index, candidate ->
                        RunDetailCandidateResponse.from(candidate, index, canSeeProviderIdentity)
                    },
                synthesis = detail.synthesis?.let { RunDetailSynthesisResponse.from(it, canSeeProviderIdentity) },
                qualitySummary = detail.qualitySummary,
                safetySummary = detail.safetySummary,
            )
        }
    }
}

/** GET /{guildId}/decision-summary */
data class DecisionSummaryResponse(
    val guildId: Long,
    val channelId: Long?,
    val recentRunCount: Int,
    val completedRunCount: Int,
    val fallbackRunCount: Int,
    val totalCandidateCount: Int,
    val acceptedCandidateCount: Int,
    val rejectedCandidateCount: Int,
    val timeoutCandidateCount: Int,
    val averageQualityScore: Double,
    val adoptionRate: Double,
    val statusCounts: Map<String, Int>,
    val riskCodes: List<String>,
    val nextActions: List<String>,
    val recentDecisions: List<com.discordassistant.central.multiresponse.application.MultiResponseDecisionItem>,
) {
    companion object {
        fun from(summary: MultiResponseDecisionSummary): DecisionSummaryResponse =
            DecisionSummaryResponse(
                guildId = summary.guildId,
                channelId = summary.channelId,
                recentRunCount = summary.recentRunCount,
                completedRunCount = summary.completedRunCount,
                fallbackRunCount = summary.fallbackRunCount,
                totalCandidateCount = summary.totalCandidateCount,
                acceptedCandidateCount = summary.acceptedCandidateCount,
                rejectedCandidateCount = summary.rejectedCandidateCount,
                timeoutCandidateCount = summary.timeoutCandidateCount,
                averageQualityScore = summary.averageQualityScore,
                adoptionRate = summary.adoptionRate,
                statusCounts = summary.statusCounts,
                riskCodes = summary.riskCodes,
                nextActions = summary.nextActions,
                recentDecisions = summary.recentDecisions,
            )
    }
}

/** GET /{guildId}/stats */
data class StatsResponse(
    val guildId: Long,
    val recentRunCount: Int,
    val completedRunCount: Int,
    val fallbackRunCount: Int,
    val timeoutCandidateCount: Int,
    val averageActualFanout: Double,
) {
    companion object {
        fun from(stats: MultiResponseDailyStats): StatsResponse =
            StatsResponse(
                guildId = stats.guildId,
                recentRunCount = stats.recentRunCount,
                completedRunCount = stats.completedRunCount,
                fallbackRunCount = stats.fallbackRunCount,
                timeoutCandidateCount = stats.timeoutCandidateCount,
                averageActualFanout = stats.averageActualFanout,
            )
    }
}

data class FanoutRecommendationProviderResponse(
    val providerUserId: Long?,
    val providerLabel: String,
    val modelName: String?,
    val qualityTier: String,
    val overloadRisk: String,
) {
    companion object {
        fun from(
            provider: MultiResponseRecommendedProvider,
            index: Int,
            audience: DashboardAudience,
        ): FanoutRecommendationProviderResponse =
            FanoutRecommendationProviderResponse(
                providerUserId = if (audience.canSeeProviderIdentity) provider.providerUserId else null,
                providerLabel = providerLabel(provider.providerUserId, index),
                modelName = provider.modelName,
                qualityTier = provider.qualityTier,
                overloadRisk = DashboardAudience.PUBLIC.risk(provider.overloadRisk),
            )
    }
}

/** GET /{guildId}/recommendation */
data class FanoutRecommendationResponse(
    val guildId: Long,
    val channelId: Long?,
    val policySource: String,
    val policyMode: String,
    val requestedCandidates: Int,
    val maxSafeCandidates: Int,
    val recommendedCandidateCount: Int,
    val fanoutAllowed: Boolean,
    val status: String,
    val reasons: List<String>,
    val providers: List<FanoutRecommendationProviderResponse>,
) {
    companion object {
        fun from(
            recommendation: MultiResponseFanoutRecommendation,
            audience: DashboardAudience,
        ): FanoutRecommendationResponse =
            FanoutRecommendationResponse(
                guildId = recommendation.guildId,
                channelId = recommendation.channelId,
                policySource = recommendation.policySource,
                policyMode = recommendation.policyMode,
                requestedCandidates = recommendation.requestedCandidates,
                maxSafeCandidates = recommendation.maxSafeCandidates,
                recommendedCandidateCount = recommendation.recommendedCandidateCount,
                fanoutAllowed = recommendation.fanoutAllowed,
                status = recommendation.status,
                reasons = recommendation.reasons,
                providers =
                    recommendation.providers.mapIndexed { index, provider ->
                        FanoutRecommendationProviderResponse.from(provider, index, audience)
                    },
            )
    }
}

/** GET /{guildId}/operations-summary (래퍼: {"summary": ...}) */
data class OperationsSummaryResponse(
    val summary: MultiResponseOperationsDashboardResponse,
) {
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
