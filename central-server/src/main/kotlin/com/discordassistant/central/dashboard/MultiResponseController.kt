package com.discordassistant.central.dashboard

import com.discordassistant.central.network.MultiResponseService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai-network/multi-response")
class MultiResponseController(
    private val service: MultiResponseService,
) {
    @PostMapping("/{guildId}/policy")
    fun savePolicy(
        @PathVariable guildId: Long,
        @RequestBody request: SaveMultiResponsePolicyRequest,
    ): Map<String, Any?> {
        val policy =
            service.savePolicy(
                guildId = guildId,
                channelId = request.channelId,
                channelAiId = request.channelAiId,
                mode = request.mode,
                maxCandidates = request.maxCandidates,
                requireDistinctModels = request.requireDistinctModels,
                providerDailyLimit = request.providerDailyLimit,
                timeoutSeconds = request.timeoutSeconds,
                synthesisEnabled = request.synthesisEnabled,
                disabledReason = request.disabledReason,
            )
        return mapOf(
            "id" to policy.id,
            "mode" to policy.mode,
            "maxCandidates" to policy.maxCandidates,
            "synthesisEnabled" to policy.synthesisEnabled,
            "disabledReason" to policy.disabledReason,
        )
    }

    @PostMapping("/{guildId}/runs")
    fun startRun(
        @PathVariable guildId: Long,
        @RequestBody request: StartMultiResponseRunRequest,
    ): Map<String, Any?> {
        val run = service.startRun(guildId, request.channelId, request.requestId, request.promptPreview, request.responseMode)
        return mapOf(
            "id" to run.id,
            "requestId" to run.requestId,
            "status" to run.status,
            "candidateCount" to run.candidateCount,
            "ragContextStatus" to run.ragContextStatus,
            "ragContextChars" to run.ragContextChars,
        )
    }

    @PostMapping("/runs/{runId}/candidates/{candidateId}")
    fun recordCandidate(
        @PathVariable runId: Long,
        @PathVariable candidateId: Long,
        @RequestBody request: RecordCandidateRequest,
    ): Map<String, Any?> {
        val candidate =
            service.recordCandidate(
                runId = runId,
                candidateId = candidateId,
                answerRef = request.answerRef,
                status = request.status,
                latencyMs = request.latencyMs,
                safetyFlags = request.safetyFlags,
                qualityScore = request.qualityScore,
            )
        return mapOf("id" to candidate.id, "status" to candidate.status, "qualityScore" to candidate.qualityScore)
    }

    @PostMapping("/runs/{runId}/synthesis")
    fun synthesize(
        @PathVariable runId: Long,
        @RequestBody request: SynthesizeRunRequest,
    ): Map<String, Any?> {
        val synthesis =
            service.synthesize(
                runId = runId,
                answerRef = request.answerRef,
                selectedCandidateIds = request.selectedCandidateIds,
                strategy = request.strategy,
                qualitySummary = request.qualitySummary,
                safetySummary = request.safetySummary,
            )
        return mapOf(
            "id" to synthesis.id,
            "status" to synthesis.status,
            "answerRef" to synthesis.answerRef,
            "strategy" to synthesis.strategy,
            "qualitySummary" to synthesis.qualitySummary,
            "safetySummary" to synthesis.safetySummary,
        )
    }

    @PostMapping("/runs/{runId}/candidates/{candidateId}/adopt")
    fun adoptCandidate(
        @PathVariable runId: Long,
        @PathVariable candidateId: Long,
        @RequestBody request: AdoptCandidateRequest,
    ): Map<String, Any?> {
        val adoption = service.adoptCandidate(runId, candidateId, request.userId, request.rating, request.reason)
        return mapOf(
            "runId" to adoption.run.id,
            "status" to adoption.run.status,
            "selectedCandidateId" to adoption.run.selectedCandidateId,
            "candidateQualityScore" to adoption.candidate.qualityScore,
            "synthesisId" to adoption.synthesis.id,
            "feedbackId" to adoption.feedbackId,
        )
    }

    @PostMapping("/runs/{runId}/complete-best")
    fun completeBest(
        @PathVariable runId: Long,
        @RequestBody request: CompleteBestMultiResponseRunRequest = CompleteBestMultiResponseRunRequest(),
    ): Map<String, Any?> {
        val completion = service.completeBestEffort(runId, request.strategy)
        return mapOf(
            "id" to completion.run.id,
            "status" to completion.run.status,
            "selectedCandidateId" to completion.run.selectedCandidateId,
            "synthesisId" to completion.synthesis?.id,
            "answerRef" to completion.synthesis?.answerRef,
            "fallbackReason" to completion.fallbackReason,
        )
    }

    @PostMapping("/runs/{runId}/fail")
    fun fail(
        @PathVariable runId: Long,
        @RequestBody request: FailMultiResponseRunRequest,
    ): Map<String, Any?> {
        val run = service.failRun(runId, request.reason)
        return mapOf("id" to run.id, "status" to run.status, "failureReason" to run.failureReason)
    }

    @PostMapping("/pseudo-stream-plan")
    fun pseudoStreamPlan(
        @RequestBody request: PseudoStreamPlanRequest,
    ): Map<String, Any?> {
        val plan = service.pseudoStreamPlan(request.answer, request.steps, request.maxDiscordChars)
        return mapOf(
            "finalLength" to plan.finalLength,
            "truncated" to plan.truncated,
            "editIntervalMs" to plan.editIntervalMs,
            "snapshots" to plan.snapshots,
            "warning" to plan.warning,
        )
    }

    @GetMapping("/{guildId}/runs")
    fun recentRuns(
        @PathVariable guildId: Long,
    ): List<Map<String, Any?>> =
        service.listRecent(guildId).map {
            mapOf(
                "id" to it.id,
                "requestId" to it.requestId,
                "channelId" to it.channelId,
                "status" to it.status,
                "candidateCount" to it.candidateCount,
                "startedAt" to it.startedAt.toString(),
                "finishedAt" to it.finishedAt?.toString(),
            )
        }

    @GetMapping("/runs/{runId}")
    fun runDetail(
        @PathVariable runId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): Map<String, Any?> {
        val detail = service.runDetail(runId)
        val canSeeProviderIdentity = audience.equals("admin", ignoreCase = true)
        return mapOf(
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
    }

    @GetMapping("/{guildId}/provider-load")
    fun providerLoad(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): List<ProviderFanoutLoadDashboardResponse> =
        service.providerFanoutLoad(guildId).mapIndexed { index, load ->
            ProviderFanoutLoadDashboardResponse.from(load, index, DashboardAudience.from(audience))
        }

    @GetMapping("/{guildId}/decision-summary")
    fun decisionSummary(
        @PathVariable guildId: Long,
        @RequestParam(required = false) channelId: Long? = null,
        @RequestParam(defaultValue = "20") limit: Int = 20,
    ): Map<String, Any?> {
        val summary = service.decisionSummary(guildId, channelId, limit)
        return mapOf(
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
    }

    @GetMapping("/{guildId}/stats")
    fun stats(
        @PathVariable guildId: Long,
    ): Map<String, Any?> {
        val stats = service.dailyStats(guildId)
        return mapOf(
            "guildId" to stats.guildId,
            "recentRunCount" to stats.recentRunCount,
            "completedRunCount" to stats.completedRunCount,
            "fallbackRunCount" to stats.fallbackRunCount,
            "timeoutCandidateCount" to stats.timeoutCandidateCount,
            "averageActualFanout" to stats.averageActualFanout,
        )
    }

    @GetMapping("/{guildId}/operations-summary")
    fun operationsSummary(
        @PathVariable guildId: Long,
        @RequestParam(required = false) channelId: Long? = null,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): Map<String, Any?> =
        mapOf(
            "summary" to
                MultiResponseOperationsDashboardResponse.from(
                    service.operationsSummary(guildId, channelId),
                    DashboardAudience.from(audience),
                ),
        )

    private fun providerLabel(
        providerUserId: Long?,
        index: Int,
    ): String = providerUserId?.let { "Provider ${kotlin.math.abs(it.hashCode()).toString(36).take(6)}" } ?: "Provider ${index + 1}"
}

data class SaveMultiResponsePolicyRequest(
    val channelId: Long? = null,
    val channelAiId: Long? = null,
    val mode: String = "single",
    val maxCandidates: Int = 1,
    val requireDistinctModels: Boolean = false,
    val providerDailyLimit: Int = 0,
    val timeoutSeconds: Int = 120,
    val synthesisEnabled: Boolean = false,
    val disabledReason: String? = null,
)

data class StartMultiResponseRunRequest(
    val channelId: Long,
    val requestId: String,
    val promptPreview: String? = null,
    val responseMode: String = "balanced",
)

data class RecordCandidateRequest(
    val answerRef: String? = null,
    val status: String = "completed",
    val latencyMs: Int? = null,
    val safetyFlags: List<String> = emptyList(),
    val qualityScore: Int? = null,
)

data class SynthesizeRunRequest(
    val answerRef: String,
    val selectedCandidateIds: List<Long> = emptyList(),
    val strategy: String = "best_by_heuristic",
    val qualitySummary: String? = null,
    val safetySummary: String? = null,
)

data class AdoptCandidateRequest(
    val userId: Long? = null,
    val rating: Int? = null,
    val reason: String? = null,
)

data class CompleteBestMultiResponseRunRequest(
    val strategy: String = "best_successful_candidate",
)

data class FailMultiResponseRunRequest(
    val reason: String,
)

data class PseudoStreamPlanRequest(
    val answer: String,
    val steps: List<Int> = emptyList(),
    val maxDiscordChars: Int = 1_900,
)

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
    val riskCodes: List<String>,
    val nextActions: List<String>,
    val providerLoads: List<ProviderFanoutLoadDashboardResponse>,
    val decisionSummary: com.discordassistant.central.network.MultiResponseDecisionSummary,
) {
    companion object {
        fun from(
            summary: com.discordassistant.central.network.MultiResponseOperationsSummary,
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
            load: com.discordassistant.central.network.ProviderFanoutLoadSummary,
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
