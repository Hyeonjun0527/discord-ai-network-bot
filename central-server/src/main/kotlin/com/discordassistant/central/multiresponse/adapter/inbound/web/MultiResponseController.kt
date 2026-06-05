package com.discordassistant.central.multiresponse.adapter.inbound.web

import com.discordassistant.central.ainetwork.application.DashboardAudience
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.AdoptCandidateRequest
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.AdoptCandidateResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.CompleteBestMultiResponseRunRequest
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.CompleteBestResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.DecisionSummaryResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.FailMultiResponseRunRequest
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.FailRunResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.FanoutRecommendationResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.OperationsSummaryResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.ProviderFanoutLoadDashboardResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.PseudoStreamPlanRequest
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.PseudoStreamPlanResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.RecentRunResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.RecordCandidateRequest
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.RecordCandidateResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.RunDetailResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.SaveMultiResponsePolicyRequest
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.SavePolicyResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.StartMultiResponseRunRequest
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.StartRunResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.StatsResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.SynthesizeResponse
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.SynthesizeRunRequest
import com.discordassistant.central.multiresponse.application.MultiResponseService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 다중응답 lean 인바운드 어댑터.
 *
 * 각 핸들러는 "파싱 → application 호출 → DTO.from()" 3단계만 수행한다.
 * 와이어 매핑(키/순서/null), audience 기반 마스킹, providerLabel 프레젠테이션은 모두 `web/dto` 응답 DTO 안에 있다.
 */
@RestController
@RequestMapping("/api/ai-network/multi-response")
class MultiResponseController(
    private val service: MultiResponseService,
) {
    @PostMapping("/{guildId}/policy")
    fun savePolicy(
        @PathVariable guildId: Long,
        @RequestBody request: SaveMultiResponsePolicyRequest,
    ): Map<String, Any?> =
        SavePolicyResponse
            .from(
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
                ),
            ).toMap()

    @PostMapping("/{guildId}/runs")
    fun startRun(
        @PathVariable guildId: Long,
        @RequestBody request: StartMultiResponseRunRequest,
    ): Map<String, Any?> =
        StartRunResponse
            .from(service.startRun(guildId, request.channelId, request.requestId, request.promptPreview, request.responseMode))
            .toMap()

    @PostMapping("/runs/{runId}/candidates/{candidateId}")
    fun recordCandidate(
        @PathVariable runId: Long,
        @PathVariable candidateId: Long,
        @RequestBody request: RecordCandidateRequest,
    ): Map<String, Any?> =
        RecordCandidateResponse
            .from(
                service.recordCandidate(
                    runId = runId,
                    candidateId = candidateId,
                    answerRef = request.answerRef,
                    status = request.status,
                    latencyMs = request.latencyMs,
                    safetyFlags = request.safetyFlags,
                    qualityScore = request.qualityScore,
                ),
            ).toMap()

    @PostMapping("/runs/{runId}/synthesis")
    fun synthesize(
        @PathVariable runId: Long,
        @RequestBody request: SynthesizeRunRequest,
    ): Map<String, Any?> =
        SynthesizeResponse
            .from(
                service.synthesize(
                    runId = runId,
                    answerRef = request.answerRef,
                    selectedCandidateIds = request.selectedCandidateIds,
                    strategy = request.strategy,
                    qualitySummary = request.qualitySummary,
                    safetySummary = request.safetySummary,
                ),
            ).toMap()

    @PostMapping("/runs/{runId}/candidates/{candidateId}/adopt")
    fun adoptCandidate(
        @PathVariable runId: Long,
        @PathVariable candidateId: Long,
        @RequestBody request: AdoptCandidateRequest,
    ): Map<String, Any?> =
        AdoptCandidateResponse
            .from(service.adoptCandidate(runId, candidateId, request.userId, request.rating, request.reason))
            .toMap()

    @PostMapping("/runs/{runId}/complete-best")
    fun completeBest(
        @PathVariable runId: Long,
        @RequestBody request: CompleteBestMultiResponseRunRequest = CompleteBestMultiResponseRunRequest(),
    ): Map<String, Any?> = CompleteBestResponse.from(service.completeBestEffort(runId, request.strategy)).toMap()

    @PostMapping("/runs/{runId}/fail")
    fun fail(
        @PathVariable runId: Long,
        @RequestBody request: FailMultiResponseRunRequest,
    ): Map<String, Any?> = FailRunResponse.from(service.failRun(runId, request.reason)).toMap()

    @PostMapping("/pseudo-stream-plan")
    fun pseudoStreamPlan(
        @RequestBody request: PseudoStreamPlanRequest,
    ): Map<String, Any?> =
        PseudoStreamPlanResponse.from(service.pseudoStreamPlan(request.answer, request.steps, request.maxDiscordChars)).toMap()

    @GetMapping("/{guildId}/runs")
    fun recentRuns(
        @PathVariable guildId: Long,
    ): List<Map<String, Any?>> = service.listRecent(guildId).map { RecentRunResponse.from(it).toMap() }

    @GetMapping("/runs/{runId}")
    fun runDetail(
        @PathVariable runId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): Map<String, Any?> = RunDetailResponse.from(service.runDetail(runId), audience).toMap()

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
    ): Map<String, Any?> = DecisionSummaryResponse.from(service.decisionSummary(guildId, channelId, limit)).toMap()

    @GetMapping("/{guildId}/stats")
    fun stats(
        @PathVariable guildId: Long,
    ): Map<String, Any?> = StatsResponse.from(service.dailyStats(guildId)).toMap()

    @GetMapping("/{guildId}/recommendation")
    fun fanoutRecommendation(
        @PathVariable guildId: Long,
        @RequestParam(required = false) channelId: Long? = null,
        @RequestParam(defaultValue = "balanced") responseMode: String = "balanced",
        @RequestParam(defaultValue = "1") requestedCandidates: Int = 1,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): Map<String, Any?> =
        FanoutRecommendationResponse
            .from(
                service.recommendFanout(guildId, channelId, responseMode, requestedCandidates),
                DashboardAudience.from(audience),
            ).toMap()

    @GetMapping("/{guildId}/operations-summary")
    fun operationsSummary(
        @PathVariable guildId: Long,
        @RequestParam(required = false) channelId: Long? = null,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): Map<String, Any?> =
        OperationsSummaryResponse
            .from(service.operationsSummary(guildId, channelId), DashboardAudience.from(audience))
            .toMap()
}
