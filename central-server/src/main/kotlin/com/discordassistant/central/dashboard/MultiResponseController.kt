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
        val run = service.startRun(guildId, request.channelId, request.requestId)
        return mapOf("id" to run.id, "requestId" to run.requestId, "status" to run.status, "candidateCount" to run.candidateCount)
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
        val synthesis = service.synthesize(runId, request.answerRef, request.selectedCandidateIds)
        return mapOf("id" to synthesis.id, "status" to synthesis.status, "answerRef" to synthesis.answerRef)
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
)

data class FailMultiResponseRunRequest(
    val reason: String,
)
