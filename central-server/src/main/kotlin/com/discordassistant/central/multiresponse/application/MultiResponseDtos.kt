package com.discordassistant.central.multiresponse.application

import java.time.Instant

// 응답/결과 DTO (행위 분해: 서비스 본체에서 분리, 같은 패키지·시그니처 불변).

data class MultiResponseRunDetail(
    val run: MultiResponseRunView,
    val candidates: List<CandidateAnswerView>,
    val synthesis: SynthesisResultView?,
    val policy: MultiResponsePolicyView?,
    val safetySummary: String,
    val qualitySummary: String,
)

/** 컨트롤러/디스코드 어댑터가 읽는 run 요약 DTO. JPA 엔티티를 web 계층에 노출하지 않기 위한 뷰. */
data class MultiResponseRunView(
    val id: Long,
    val guildId: Long,
    val channelId: Long,
    val requestId: String,
    val policyId: Long?,
    val status: String,
    val candidateCount: Int,
    val selectedCandidateId: Long?,
    val ragContextStatus: String?,
    val ragContextSourceIds: String?,
    val ragContextChars: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val failureReason: String?,
)

/** 컨트롤러/디스코드 어댑터가 읽는 정책 요약 DTO. */
data class MultiResponsePolicyView(
    val id: Long,
    val guildId: Long,
    val channelId: Long?,
    val mode: String,
    val maxCandidates: Int,
    val requireDistinctModels: Boolean,
    val providerDailyLimit: Int,
    val timeoutSeconds: Int,
    val synthesisEnabled: Boolean,
    val disabledReason: String?,
)

/** 컨트롤러가 읽는 후보 요약 DTO. */
data class CandidateAnswerView(
    val id: Long,
    val runId: Long,
    val providerUserId: Long?,
    val modelName: String?,
    val answerRef: String?,
    val status: String,
    val latencyMs: Int?,
    val safetyFlags: String?,
    val qualityScore: Int?,
)

/** 컨트롤러가 읽는 합성 결과 요약 DTO. */
data class SynthesisResultView(
    val id: Long,
    val runId: Long,
    val answerRef: String?,
    val status: String,
    val selectedCandidateIds: String?,
    val strategy: String,
    val qualitySummary: String?,
    val safetySummary: String?,
)

data class MultiResponseDailyStats(
    val guildId: Long,
    val recentRunCount: Int,
    val completedRunCount: Int,
    val fallbackRunCount: Int,
    val timeoutCandidateCount: Int,
    val averageActualFanout: Double,
)

data class MultiResponseFanoutRecommendation(
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
    val providers: List<MultiResponseRecommendedProvider>,
) {
    companion object {
        fun disabled(
            guildId: Long,
            channelId: Long?,
            policySource: String,
            policyMode: String,
            reason: String,
        ): MultiResponseFanoutRecommendation =
            MultiResponseFanoutRecommendation(
                guildId = guildId,
                channelId = channelId,
                policySource = policySource,
                policyMode = policyMode,
                requestedCandidates = 0,
                maxSafeCandidates = 0,
                recommendedCandidateCount = 0,
                fanoutAllowed = false,
                status = "disabled_by_policy",
                reasons = listOf(reason),
                providers = emptyList(),
            )
    }
}

data class MultiResponseRecommendedProvider(
    val providerUserId: Long,
    val modelName: String?,
    val qualityTier: String,
    val overloadRisk: String,
)

data class MultiResponseDecisionSummary(
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
    val recentDecisions: List<MultiResponseDecisionItem>,
) {
    companion object {
        fun empty(
            guildId: Long,
            channelId: Long?,
            reason: String,
        ): MultiResponseDecisionSummary =
            MultiResponseDecisionSummary(
                guildId = guildId,
                channelId = channelId,
                recentRunCount = 0,
                completedRunCount = 0,
                fallbackRunCount = 0,
                totalCandidateCount = 0,
                acceptedCandidateCount = 0,
                rejectedCandidateCount = 0,
                timeoutCandidateCount = 0,
                averageQualityScore = 0.0,
                adoptionRate = 0.0,
                statusCounts = mapOf("disabled" to 1),
                riskCodes = listOf(reason),
                nextActions = listOf("AI_NETWORK_MULTI_RESPONSE_DASHBOARD_ENABLED 값을 확인하세요."),
                recentDecisions = emptyList(),
            )
    }
}

data class MultiResponseDecisionItem(
    val runId: Long,
    val requestId: String,
    val channelId: Long,
    val candidateId: Long?,
    val status: String,
    val qualityScore: Int?,
    val selected: Boolean,
    val reason: String,
    val strategy: String?,
)

data class MultiResponseOperationsSummary(
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
    val providerLoads: List<ProviderFanoutLoadSummary>,
    val decisionSummary: MultiResponseDecisionSummary,
) {
    companion object {
        fun disabled(
            guildId: Long,
            channelId: Long? = null,
            reason: String = "multi_response_dashboard_disabled",
        ): MultiResponseOperationsSummary =
            MultiResponseOperationsSummary(
                guildId = guildId,
                channelId = channelId,
                status = "disabled",
                safeToEnableAdvanced = false,
                recentRunCount = 0,
                completedRunCount = 0,
                fallbackRunCount = 0,
                averageActualFanout = 0.0,
                acceptedCandidateCount = 0,
                timeoutCandidateCount = 0,
                rejectedCandidateCount = 0,
                highLoadProviderCount = 0,
                criticalLoadProviderCount = 0,
                ragFallbackRunCount = 0,
                blockedSensitiveRunCount = 0,
                noProviderRunCount = 0,
                providerProtectionBlockedCount = 0,
                recentProviderProtectionReasons = emptyList(),
                riskCodes = listOf(reason),
                nextActions = listOf("다중응답 대시보드가 비활성화되어 운영 통계를 숨겼어요."),
                providerLoads = emptyList(),
                decisionSummary = MultiResponseDecisionSummary.empty(guildId, channelId, reason),
            )
    }
}

data class ProviderFanoutLoadSummary(
    val guildId: Long,
    val providerUserId: Long,
    val candidateCount: Int,
    val completedCount: Int,
    val timeoutCount: Int,
    val failedCount: Int,
    val averageLatencyMs: Double,
    val averageQualityScore: Double,
    val loadRisk: String,
    val runIds: List<Long>,
)

data class MultiResponseCompletion(
    val run: MultiResponseRunView,
    val synthesis: SynthesisResultView?,
    val fallbackReason: String?,
)

data class CandidateAdoptionResult(
    val run: MultiResponseRunView,
    val candidate: CandidateAnswerView,
    val synthesis: SynthesisResultView,
    val feedbackId: Long?,
)

data class PseudoStreamPlan(
    val finalLength: Int,
    val truncated: Boolean,
    val editIntervalMs: Int,
    val snapshots: List<PseudoStreamSnapshot>,
    val warning: String?,
)

data class PseudoStreamSnapshot(
    val sequence: Int,
    val percent: Int,
    val content: String,
    val charCount: Int,
    val final: Boolean,
)
