package com.discordassistant.central.dashboard

import com.discordassistant.central.network.MultiResponseService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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
            )
        return mapOf("id" to policy.id, "mode" to policy.mode, "maxCandidates" to policy.maxCandidates)
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
    ): Map<String, Any?> {
        val detail = service.runDetail(runId)
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
                    )
                },
            "candidates" to
                detail.candidates.map {
                    mapOf(
                        "id" to it.id,
                        "providerUserId" to it.providerUserId,
                        "modelName" to it.modelName,
                        "status" to it.status,
                        "latencyMs" to it.latencyMs,
                        "qualityScore" to it.qualityScore,
                        "safetyFlags" to it.safetyFlags,
                        "answerRef" to it.answerRef,
                    )
                },
            "synthesis" to
                detail.synthesis?.let {
                    mapOf(
                        "id" to it.id,
                        "status" to it.status,
                        "strategy" to it.strategy,
                        "answerRef" to it.answerRef,
                        "selectedCandidateIds" to it.selectedCandidateIds,
                        "qualitySummary" to it.qualitySummary,
                        "safetySummary" to it.safetySummary,
                    )
                },
            "qualitySummary" to detail.qualitySummary,
            "safetySummary" to detail.safetySummary,
        )
    }

    @GetMapping("/{guildId}/provider-load")
    fun providerLoad(
        @PathVariable guildId: Long,
    ): List<Map<String, Any?>> =
        service.providerFanoutLoad(guildId).map {
            mapOf(
                "guildId" to it.guildId,
                "providerUserId" to it.providerUserId,
                "candidateCount" to it.candidateCount,
                "completedCount" to it.completedCount,
                "timeoutCount" to it.timeoutCount,
                "failedCount" to it.failedCount,
                "averageLatencyMs" to it.averageLatencyMs,
                "averageQualityScore" to it.averageQualityScore,
                "loadRisk" to it.loadRisk,
                "runIds" to it.runIds,
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
