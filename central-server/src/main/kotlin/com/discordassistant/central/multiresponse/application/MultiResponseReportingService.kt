package com.discordassistant.central.multiresponse.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponseRunEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponseRunRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.SynthesisResultRepository
import com.discordassistant.central.multiresponse.domain.model.CandidateStatus
import com.discordassistant.central.multiresponse.domain.model.FanoutLoadRisk
import com.discordassistant.central.multiresponse.domain.model.MultiResponseRunStatus
import com.discordassistant.central.shared.ContentSafety.BLOCKING_SAFETY_FLAGS
import org.springframework.stereotype.Service

/**
 * read-only 리포팅/분석 책임을 [MultiResponseService] 에서 분리한 서비스.
 *
 * [MultiResponseService] 의 read 전용 public 메서드는 같은 시그니처로 이 서비스에 위임한다
 * (동작 불변). DTO 는 옮기지 않고 network 패키지에 그대로 둔다.
 */
@Service
class MultiResponseReportingService(
    private val policies: MultiResponsePolicyRepository,
    private val runs: MultiResponseRunRepository,
    private val candidates: CandidateAnswerRepository,
    private val syntheses: SynthesisResultRepository,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun listRecent(guildId: Long): List<MultiResponseRunView> {
        featureGate.requireMultiResponseDashboardEnabled()
        return runs.findTop20ByGuildIdOrderByStartedAtDesc(guildId).map { it.toView() }
    }

    fun runDetail(runId: Long): MultiResponseRunDetail {
        featureGate.requireMultiResponseDashboardEnabled()
        val run = runs.findById(runId).orElseThrow { IllegalArgumentException("run not found: $runId") }
        val runCandidates = candidates.findByRunId(runId)
        return MultiResponseRunDetail(
            run = run.toView(),
            candidates = runCandidates.map { it.toView() },
            synthesis = syntheses.findByRunId(runId)?.toView(),
            policy = run.policyId?.let { policies.findById(it).orElse(null)?.toView() },
            safetySummary = summarizeSafety(runCandidates),
            qualitySummary = summarizeQuality(runCandidates),
        )
    }

    fun providerFanoutLoad(guildId: Long): List<ProviderFanoutLoadSummary> {
        featureGate.requireMultiResponseDashboardEnabled()
        val recentRuns = runs.findTop20ByGuildIdOrderByStartedAtDesc(guildId)
        val runIds = recentRuns.map { it.id }.toSet()
        if (runIds.isEmpty()) return emptyList()
        val runCandidates = recentRuns.flatMap { candidates.findByRunId(it.id) }
        return runCandidates
            .filter { it.providerUserId != null }
            .groupBy { it.providerUserId!! }
            .map { (providerUserId, providerCandidates) ->
                val latencyValues = providerCandidates.mapNotNull { it.latencyMs }
                val timeoutCount = providerCandidates.count { it.status == CandidateStatus.TIMEOUT }
                val failedCount =
                    providerCandidates.count {
                        it.status == CandidateStatus.FAILED || it.status == CandidateStatus.REJECTED
                    }
                val completedCount = providerCandidates.count { it.status == CandidateStatus.COMPLETED }
                ProviderFanoutLoadSummary(
                    guildId = guildId,
                    providerUserId = providerUserId,
                    candidateCount = providerCandidates.size,
                    completedCount = completedCount,
                    timeoutCount = timeoutCount,
                    failedCount = failedCount,
                    averageLatencyMs = latencyValues.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                    averageQualityScore = providerCandidates.mapNotNull { it.qualityScore }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                    loadRisk =
                        FanoutLoadRisk.classify(providerCandidates.size, timeoutCount, failedCount, latencyValues).wire,
                    runIds = providerCandidates.map { it.runId }.filter { it in runIds }.distinct(),
                )
            }.sortedWith(
                compareByDescending<ProviderFanoutLoadSummary> { FanoutLoadRisk.rankOf(it.loadRisk) }
                    .thenByDescending { it.candidateCount }
                    .thenBy { it.providerUserId },
            )
    }

    fun decisionSummary(
        guildId: Long,
        channelId: Long? = null,
        limit: Int = 20,
    ): MultiResponseDecisionSummary {
        featureGate.requireMultiResponseDashboardEnabled()
        val recent =
            runs
                .findTop20ByGuildIdOrderByStartedAtDesc(guildId)
                .filter { channelId == null || it.channelId == channelId }
        val runCandidates = recent.flatMap { run -> candidates.findByRunId(run.id).map { run to it } }
        val synthesesByRunId = recent.mapNotNull { run -> syntheses.findByRunId(run.id)?.let { run.id to it } }.toMap()
        val completedRuns = recent.count { it.status == MultiResponseRunStatus.COMPLETED }
        val fallbackRuns = recent.count { it.status in FALLBACK_RUN_STATUSES }
        val acceptedCandidates =
            runCandidates.count { (run, candidate) ->
                run.selectedCandidateId == candidate.id ||
                    synthesesByRunId[run.id]
                        ?.selectedCandidateIds
                        ?.toIdSet()
                        .orEmpty()
                        .contains(candidate.id)
            }
        val timeoutCandidates = runCandidates.count { it.second.status == CandidateStatus.TIMEOUT }
        val rejectedCandidates =
            runCandidates.count { (_, candidate) ->
                candidate.status == CandidateStatus.FAILED ||
                    candidate.status == CandidateStatus.REJECTED ||
                    candidate.hasBlockingSafetyFlag()
            }
        val qualityScores = runCandidates.mapNotNull { it.second.qualityScore }
        val statusCounts = runCandidates.groupingBy { it.second.status.wire }.eachCount()
        val completedCandidates = runCandidates.count { it.second.status == CandidateStatus.COMPLETED }
        val adoptionRate = if (completedCandidates == 0) 0.0 else acceptedCandidates.toDouble() / completedCandidates
        val timeoutRate = if (runCandidates.isEmpty()) 0.0 else timeoutCandidates.toDouble() / runCandidates.size
        val averageQualityScore = qualityScores.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        // 위험 신호 규칙(OCP): 새 위험은 이 리스트에 한 줄 추가하면 된다 — 평가/누적 로직은 수정하지 않는다.
        // 조건·코드·문구·평가 순서는 분해 이전과 1바이트도 다르지 않다.
        val riskRules =
            listOf(
                RiskSignal(recent.isEmpty(), "no_recent_runs", "다중 응답 실행 이력이 쌓이면 후보 선택 품질을 분석할 수 있어요."),
                RiskSignal(runCandidates.isEmpty() && recent.isNotEmpty(), "no_candidates", "온라인 Provider와 fan-out 참여 태그를 확인하세요."),
                RiskSignal(timeoutRate >= 0.25, "high_timeout_rate", "응답 속도/품질 모드를 낮추거나 과부하 Provider를 보호하세요."),
                RiskSignal(qualityScores.isNotEmpty() && averageQualityScore < 60.0, "low_quality", "채널 AI 프롬프트, 지식 베이스, 모델 정책을 점검하세요."),
                RiskSignal(completedRuns > 0 && acceptedCandidates == 0, "no_selected_candidate", "완료된 실행에 선택 후보나 합성 결과가 기록되는지 확인하세요."),
                RiskSignal(
                    recent.any { it.ragContextStatus?.startsWith("fallback") == true },
                    "rag_context_fallback",
                    "채널 지식 베이스 색인 상태와 검색 범위를 점검하세요.",
                ),
            )
        val riskCodes = mutableListOf<String>()
        val nextActions = mutableListOf<String>()
        riskRules.filter { it.active }.forEach {
            riskCodes += it.code
            nextActions += it.nextAction
        }
        val decisions =
            recent
                .flatMap { run ->
                    val selectedIds = synthesesByRunId[run.id]?.selectedCandidateIds.toIdSet()
                    val runSynthesis = synthesesByRunId[run.id]
                    val runCandidatesForRun = runCandidates.filter { it.first.id == run.id }.map { it.second }
                    if (runCandidatesForRun.isEmpty()) {
                        listOf(
                            MultiResponseDecisionItem(
                                runId = run.id,
                                requestId = run.requestId,
                                channelId = run.channelId,
                                candidateId = null,
                                status = run.status.wire,
                                qualityScore = null,
                                selected = false,
                                reason = run.failureReason ?: run.ragContextStatus ?: "no candidate recorded",
                                strategy = runSynthesis?.strategy,
                            ),
                        )
                    } else {
                        runCandidatesForRun.map { candidate ->
                            val selected = run.selectedCandidateId == candidate.id || selectedIds.contains(candidate.id)
                            MultiResponseDecisionItem(
                                runId = run.id,
                                requestId = run.requestId,
                                channelId = run.channelId,
                                candidateId = candidate.id,
                                status = candidate.status.wire,
                                qualityScore = candidate.qualityScore,
                                selected = selected,
                                reason = decisionReason(run, candidate, selected),
                                strategy = runSynthesis?.strategy,
                            )
                        }
                    }
                }.take(limit.coerceIn(1, 50))
        return MultiResponseDecisionSummary(
            guildId = guildId,
            channelId = channelId,
            recentRunCount = recent.size,
            completedRunCount = completedRuns,
            fallbackRunCount = fallbackRuns,
            totalCandidateCount = runCandidates.size,
            acceptedCandidateCount = acceptedCandidates,
            rejectedCandidateCount = rejectedCandidates,
            timeoutCandidateCount = timeoutCandidates,
            averageQualityScore = averageQualityScore,
            adoptionRate = adoptionRate,
            statusCounts = statusCounts,
            riskCodes = riskCodes.distinct(),
            nextActions = nextActions.distinct(),
            recentDecisions = decisions,
        )
    }

    fun operationsSummary(
        guildId: Long,
        channelId: Long? = null,
    ): MultiResponseOperationsSummary {
        featureGate.requireMultiResponseDashboardEnabled()
        val stats = dailyStats(guildId)
        val decisions = decisionSummary(guildId, channelId, limit = 20)
        val providerLoads = providerFanoutLoad(guildId)
        val recentRuns =
            runs
                .findTop20ByGuildIdOrderByStartedAtDesc(guildId)
                .filter { channelId == null || it.channelId == channelId }
        val criticalLoadCount = providerLoads.count { it.loadRisk.equals("critical", ignoreCase = true) }
        val highLoadCount =
            providerLoads.count {
                it.loadRisk.equals("high", ignoreCase = true) || it.loadRisk.equals("critical", ignoreCase = true)
            }
        val ragFallbackCount = recentRuns.count { it.ragContextStatus?.startsWith("fallback") == true }
        val blockedSensitiveCount = recentRuns.count { it.status == MultiResponseRunStatus.BLOCKED_SENSITIVE }
        val noProviderCount = recentRuns.count { it.status == MultiResponseRunStatus.NO_PROVIDER }
        val providerProtectionRuns =
            recentRuns.filter {
                it.status == MultiResponseRunStatus.NO_PROVIDER ||
                    it.failureReason.isProviderProtectionReason()
            }
        val providerProtectionReasons =
            providerProtectionRuns
                .mapNotNull { it.failureReason?.trim()?.takeIf { reason -> reason.isNotBlank() } }
                .distinct()
                .take(5)
        val riskCodes =
            (
                decisions.riskCodes +
                    listOfNotNull(
                        "provider_fanout_load_critical".takeIf { criticalLoadCount > 0 },
                        "provider_fanout_load_high".takeIf { highLoadCount > criticalLoadCount },
                        "rag_context_fallback".takeIf { ragFallbackCount > 0 },
                        "blocked_sensitive_prompts".takeIf { blockedSensitiveCount > 0 },
                        "no_provider_capacity".takeIf { noProviderCount > 0 },
                        "provider_protection_blocked".takeIf { providerProtectionRuns.isNotEmpty() },
                    )
            ).distinct()
        val nextActions =
            (
                decisions.nextActions +
                    listOfNotNull(
                        "과부하 Provider의 다중 응답 fan-out을 줄이고 안전 한도를 점검하세요.".takeIf {
                            criticalLoadCount > 0 || highLoadCount > 0
                        },
                        "채널 지식 베이스 색인 상태와 검색 범위를 점검하세요.".takeIf { ragFallbackCount > 0 },
                        "민감정보처럼 보이는 질문은 단일 안전 경로로 안내하고 Provider fan-out을 차단하세요.".takeIf {
                            blockedSensitiveCount > 0
                        },
                        "온라인 Provider, fan-out 참여 태그, 모델 정책을 확인하세요.".takeIf { noProviderCount > 0 },
                        "Provider 보호로 차단된 요청이 있습니다. 후보 수/깊은 답변/가용 Provider를 점검하세요.".takeIf {
                            providerProtectionRuns.isNotEmpty()
                        },
                    )
            ).distinct()
        val status =
            when {
                criticalLoadCount > 0 || noProviderCount > 0 -> "blocked"
                riskCodes.isNotEmpty() || highLoadCount > 0 -> "warning"
                stats.recentRunCount == 0 -> "empty"
                else -> "ready"
            }
        val unsafeAdvancedRisks =
            setOf(
                "high_timeout_rate",
                "low_quality",
                "no_candidates",
                "rag_context_fallback",
                "provider_fanout_load_critical",
                "provider_fanout_load_high",
                "no_provider_capacity",
            )
        return MultiResponseOperationsSummary(
            guildId = guildId,
            channelId = channelId,
            status = status,
            safeToEnableAdvanced = status == "ready" && riskCodes.none { it in unsafeAdvancedRisks },
            recentRunCount = stats.recentRunCount,
            completedRunCount = stats.completedRunCount,
            fallbackRunCount = stats.fallbackRunCount,
            averageActualFanout = stats.averageActualFanout,
            acceptedCandidateCount = decisions.acceptedCandidateCount,
            timeoutCandidateCount = stats.timeoutCandidateCount,
            rejectedCandidateCount = decisions.rejectedCandidateCount,
            highLoadProviderCount = highLoadCount,
            criticalLoadProviderCount = criticalLoadCount,
            ragFallbackRunCount = ragFallbackCount,
            blockedSensitiveRunCount = blockedSensitiveCount,
            noProviderRunCount = noProviderCount,
            providerProtectionBlockedCount = providerProtectionRuns.size,
            recentProviderProtectionReasons = providerProtectionReasons,
            riskCodes = riskCodes,
            nextActions = nextActions,
            providerLoads = providerLoads,
            decisionSummary = decisions,
        )
    }

    fun dailyStats(guildId: Long): MultiResponseDailyStats {
        featureGate.requireMultiResponseDashboardEnabled()
        val recent = runs.findTop20ByGuildIdOrderByStartedAtDesc(guildId)
        val completed = recent.count { it.status == MultiResponseRunStatus.COMPLETED }
        val fallback = recent.count { it.status in FALLBACK_RUN_STATUSES }
        val timeoutCandidates =
            recent
                .flatMap { candidates.findByRunId(it.id) }
                .count { it.status == CandidateStatus.TIMEOUT }
        val actualFanout =
            recent
                .map { it.candidateCount }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?: 0.0
        return MultiResponseDailyStats(
            guildId = guildId,
            recentRunCount = recent.size,
            completedRunCount = completed,
            fallbackRunCount = fallback,
            timeoutCandidateCount = timeoutCandidates,
            averageActualFanout = actualFanout,
        )
    }

    private fun decisionReason(
        run: MultiResponseRunEntity,
        candidate: CandidateAnswerEntity,
        selected: Boolean,
    ): String =
        when {
            selected -> "selected_by_${syntheses.findByRunId(run.id)?.strategy ?: "run"}"
            candidate.hasBlockingSafetyFlag() -> "blocked_by_safety_flags"
            candidate.status == CandidateStatus.TIMEOUT -> "candidate_timeout"
            candidate.status == CandidateStatus.FAILED -> "candidate_failed"
            candidate.status == CandidateStatus.REJECTED -> "candidate_rejected"
            candidate.status == CandidateStatus.COMPLETED -> "completed_not_selected"
            else -> run.failureReason ?: "candidate_${candidate.status.wire}"
        }

    private fun String?.toIdSet(): Set<Long> =
        this
            .orEmpty()
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()

    private fun String?.isProviderProtectionReason(): Boolean {
        val text = this?.trim().orEmpty()
        if (text.isBlank()) return false
        return PROVIDER_PROTECTION_REASON_PATTERNS.any { it.containsMatchIn(text) }
    }

    // 공유 헬퍼: write 경로(synthesize/adoptCandidate)도 쓰므로 MultiResponseService 에도 동일 사본이 남는다.
    private fun CandidateAnswerEntity.hasBlockingSafetyFlag(): Boolean =
        safetyFlags
            .orEmpty()
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .any { it in BLOCKING_SAFETY_FLAGS }

    private fun summarizeSafety(runCandidates: List<CandidateAnswerEntity>): String {
        val flags =
            runCandidates
                .flatMap { it.safetyFlags.orEmpty().split(",") }
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals("ok", ignoreCase = true) }
                .distinct()
        return if (flags.isEmpty()) "no candidate safety flags" else flags.joinToString(",")
    }

    private fun summarizeQuality(runCandidates: List<CandidateAnswerEntity>): String {
        val scores = runCandidates.mapNotNull { it.qualityScore }
        if (scores.isEmpty()) return "quality score unavailable"
        val average = scores.average()
        val best = scores.max()
        return "avg=${"%.1f".format(average)}, best=$best, scored=${scores.size}"
    }

    /** 위험 신호 1건(OCP 규칙): active 면 code·nextAction 을 요약에 더한다. */
    private data class RiskSignal(
        val active: Boolean,
        val code: String,
        val nextAction: String,
    )

    private companion object {
        val FALLBACK_RUN_STATUSES =
            setOf(
                MultiResponseRunStatus.NO_PROVIDER,
                MultiResponseRunStatus.BLOCKED_SENSITIVE,
                MultiResponseRunStatus.FAILED,
            )
        val PROVIDER_PROTECTION_REASON_PATTERNS =
            listOf(
                Regex("(?i)provider protection|provider safety|safe provider|no_provider_capacity"),
                Regex("Provider 보호|안전 Provider|과부하 Provider"),
            )
    }
}
