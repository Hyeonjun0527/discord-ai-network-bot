package com.discordassistant.central.network

import com.discordassistant.central.persistence.AiFeedbackEntity
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.CandidateAnswerEntity
import com.discordassistant.central.persistence.CandidateAnswerRepository
import com.discordassistant.central.persistence.MultiResponsePolicyEntity
import com.discordassistant.central.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.persistence.MultiResponseRunEntity
import com.discordassistant.central.persistence.MultiResponseRunRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.persistence.SynthesisResultEntity
import com.discordassistant.central.persistence.SynthesisResultRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class MultiResponseService(
    private val policies: MultiResponsePolicyRepository,
    private val runs: MultiResponseRunRepository,
    private val candidates: CandidateAnswerRepository,
    private val syntheses: SynthesisResultRepository,
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val feedbacks: AiFeedbackRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
    private val safety: ProviderSafetyService? = null,
    private val knowledgeSearch: KnowledgeSearchService? = null,
) {
    @Transactional
    fun savePolicy(
        guildId: Long,
        channelId: Long?,
        channelAiId: Long?,
        mode: String,
        maxCandidates: Int,
        requireDistinctModels: Boolean,
        providerDailyLimit: Int,
        timeoutSeconds: Int,
        synthesisEnabled: Boolean,
    ): MultiResponsePolicyEntity {
        featureGate.requireMultiResponseEnabled()
        val now = Instant.now(clock)
        val existing =
            if (channelId == null) {
                policies.findByGuildIdAndChannelIdIsNull(guildId)
            } else {
                policies.findByGuildIdAndChannelId(guildId, channelId)
            }
        val entity = existing ?: MultiResponsePolicyEntity(guildId = guildId, channelId = channelId, createdAt = now)
        entity.channelAiId = channelAiId
        entity.mode = mode.trim().ifBlank { "single" }
        entity.maxCandidates = maxCandidates.coerceIn(1, AI_NETWORK_MAX_CANDIDATES)
        entity.requireDistinctModels = requireDistinctModels
        entity.providerDailyLimit = providerDailyLimit.coerceAtLeast(0)
        entity.timeoutSeconds = timeoutSeconds.coerceIn(10, 300)
        entity.synthesisEnabled = synthesisEnabled
        entity.updatedAt = now
        return policies.save(entity)
    }

    @Transactional
    fun startRun(
        guildId: Long,
        channelId: Long,
        requestId: String = newRequestId(),
        promptPreview: String? = null,
        responseMode: String = "balanced",
    ): MultiResponseRunEntity {
        featureGate.requireMultiResponseEnabled()
        val policy =
            policies.findByGuildIdAndChannelId(guildId, channelId)
                ?: policies.findByGuildIdAndChannelIdIsNull(guildId)
                ?: savePolicy(
                    guildId = guildId,
                    channelId = channelId,
                    channelAiId = null,
                    mode = "single",
                    maxCandidates = 1,
                    requireDistinctModels = false,
                    providerDailyLimit = 0,
                    timeoutSeconds = 120,
                    synthesisEnabled = false,
                )
        val run =
            runs.save(
                MultiResponseRunEntity(
                    guildId = guildId,
                    channelId = channelId,
                    requestId = requestId,
                    policyId = policy.id,
                    status = "planned",
                    startedAt = Instant.now(clock),
                ),
            )
        if (promptPreview.isSensitivePrompt()) {
            run.status = "blocked_sensitive"
            run.failureReason = "multi-response fan-out disabled for sensitive-looking prompt"
            run.ragContextStatus = "skipped_sensitive_prompt"
            run.finishedAt = Instant.now(clock)
            return runs.save(run)
        }
        applyRagContextSnapshot(run, promptPreview, responseMode)
        val executionPlan = safety?.executionPlan(guildId, policy.mode, policy.maxCandidates)
        if (executionPlan != null && executionPlan.maxSafeCandidates == 0) {
            run.status = "no_provider"
            run.failureReason = executionPlan.reasons.joinToString(" ")
            run.finishedAt = Instant.now(clock)
            return runs.save(run)
        }
        val selectedProviders =
            selectProviders(
                guildId = guildId,
                policy = policy,
                maxCandidates = executionPlan?.maxSafeCandidates ?: policy.maxCandidates,
                fanoutAllowed = executionPlan?.fanoutAllowed ?: true,
            )
        selectedProviders.forEach { provider ->
            val firstModel = provider.firstModel()
            candidates.save(
                CandidateAnswerEntity(
                    runId = run.id,
                    providerUserId = provider.providerUserId,
                    modelName = firstModel,
                    status = "planned",
                    createdAt = Instant.now(clock),
                ),
            )
        }
        run.candidateCount = selectedProviders.size
        run.status = if (selectedProviders.isEmpty()) "no_provider" else "running"
        return runs.save(run)
    }

    @Transactional
    fun recordCandidate(
        runId: Long,
        candidateId: Long,
        answerRef: String?,
        status: String,
        latencyMs: Int?,
        safetyFlags: List<String>,
        qualityScore: Int?,
    ): CandidateAnswerEntity {
        featureGate.requireMultiResponseEnabled()
        val candidate =
            candidates.findByRunIdAndId(runId, candidateId)
                ?: throw IllegalArgumentException("candidate not found: run=$runId candidate=$candidateId")
        candidate.answerRef = answerRef
        candidate.status = status.trim().ifBlank { "completed" }
        candidate.latencyMs = latencyMs
        candidate.safetyFlags = safetyFlags.joinToString(",").ifBlank { null }
        candidate.qualityScore = qualityScore
        return candidates.save(candidate)
    }

    @Transactional
    fun synthesize(
        runId: Long,
        answerRef: String,
        selectedCandidateIds: List<Long>,
        strategy: String = "best_by_heuristic",
        qualitySummary: String? = null,
        safetySummary: String? = null,
    ): SynthesisResultEntity {
        featureGate.requireMultiResponseEnabled()
        val run = runs.findById(runId).orElseThrow { IllegalArgumentException("run not found: $runId") }
        val runCandidates = candidates.findByRunId(runId)
        require(selectedCandidateIds.all { selectedId -> runCandidates.any { it.id == selectedId } }) {
            "selected candidates must belong to run: $runId"
        }
        val now = Instant.now(clock)
        val synthesis =
            syntheses.findByRunId(runId)
                ?: SynthesisResultEntity(runId = runId, createdAt = now)
        synthesis.answerRef = answerRef.trim()
        synthesis.status = "completed"
        synthesis.selectedCandidateIds = selectedCandidateIds.joinToString(",")
        synthesis.strategy = strategy.trim().ifBlank { "best_by_heuristic" }.take(80)
        synthesis.qualitySummary = qualitySummary?.trim()?.take(1000)?.ifBlank { null } ?: summarizeQuality(runCandidates)
        synthesis.safetySummary = safetySummary?.trim()?.take(1000)?.ifBlank { null } ?: summarizeSafety(runCandidates)
        val saved = syntheses.save(synthesis)
        run.status = "completed"
        run.selectedCandidateId = selectedCandidateIds.firstOrNull()
        run.finishedAt = now
        runs.save(run)
        return saved
    }

    @Transactional
    fun completeBestEffort(
        runId: Long,
        strategy: String = "best_successful_candidate",
    ): MultiResponseCompletion {
        featureGate.requireMultiResponseEnabled()
        val run = runs.findById(runId).orElseThrow { IllegalArgumentException("run not found: $runId") }
        val runCandidates = candidates.findByRunId(runId)
        val successful =
            runCandidates
                .filter { it.status.equals("completed", ignoreCase = true) }
                .filter { !it.answerRef.isNullOrBlank() }
                .filter { !it.hasBlockingSafetyFlag() }
                .sortedWith(
                    compareByDescending<CandidateAnswerEntity> { it.qualityScore ?: Int.MIN_VALUE }
                        .thenBy { it.latencyMs ?: Int.MAX_VALUE }
                        .thenBy { it.id },
                )
        val best = successful.firstOrNull()
        if (best == null) {
            run.status = "failed"
            run.failureReason = failureSummary(runCandidates)
            run.finishedAt = Instant.now(clock)
            runs.save(run)
            return MultiResponseCompletion(run = run, synthesis = null, fallbackReason = run.failureReason)
        }
        val synthesis =
            synthesize(
                runId = runId,
                answerRef = best.answerRef!!,
                selectedCandidateIds = listOf(best.id),
                strategy = strategy,
                qualitySummary = null,
                safetySummary = null,
            )
        val savedRun = runs.findById(runId).orElse(run)
        return MultiResponseCompletion(run = savedRun, synthesis = synthesis, fallbackReason = null)
    }

    @Transactional
    fun adoptCandidate(
        runId: Long,
        candidateId: Long,
        userId: Long?,
        rating: Int?,
        reason: String?,
    ): CandidateAdoptionResult {
        featureGate.requireMultiResponseEnabled()
        val now = Instant.now(clock)
        val run = runs.findById(runId).orElseThrow { IllegalArgumentException("run not found: $runId") }
        val candidate =
            candidates.findByRunIdAndId(runId, candidateId)
                ?: throw IllegalArgumentException("candidate not found: run=$runId candidate=$candidateId")
        require(candidate.status.equals("completed", ignoreCase = true)) { "only completed candidates can be adopted" }
        require(!candidate.answerRef.isNullOrBlank()) { "candidate answerRef is required for adoption" }
        val normalizedRating = rating?.coerceIn(-1, 1)
        if (normalizedRating != null) {
            candidate.qualityScore =
                when {
                    normalizedRating > 0 -> maxOf(candidate.qualityScore ?: 0, 100)
                    normalizedRating < 0 -> minOf(candidate.qualityScore ?: 0, 0)
                    else -> candidate.qualityScore ?: 50
                }
            candidates.save(candidate)
        }
        val synthesis =
            syntheses.findByRunId(runId)
                ?: SynthesisResultEntity(runId = runId, createdAt = now)
        synthesis.answerRef = candidate.answerRef
        synthesis.status = "completed"
        synthesis.selectedCandidateIds = candidate.id.toString()
        synthesis.strategy = "user_selected_candidate"
        synthesis.qualitySummary = "user selected candidate #${candidate.id}"
        synthesis.safetySummary = summarizeSafety(listOf(candidate))
        val savedSynthesis = syntheses.save(synthesis)
        run.selectedCandidateId = candidate.id
        run.status = "completed"
        run.finishedAt = run.finishedAt ?: now
        val savedRun = runs.save(run)
        val feedback =
            feedbacks?.save(
                AiFeedbackEntity(
                    guildId = run.guildId,
                    channelId = run.channelId,
                    requestId = run.requestId,
                    userId = userId,
                    rating = normalizedRating,
                    feedbackType = "candidate_adoption",
                    reason = sanitizeText(reason, maxLength = 500),
                    status = "open",
                    createdAt = now,
                ),
            )
        return CandidateAdoptionResult(
            run = savedRun,
            candidate = candidate,
            synthesis = savedSynthesis,
            feedbackId = feedback?.id,
        )
    }

    @Transactional
    fun failRun(
        runId: Long,
        reason: String,
    ): MultiResponseRunEntity {
        featureGate.requireMultiResponseEnabled()
        val run = runs.findById(runId).orElseThrow { IllegalArgumentException("run not found: $runId") }
        run.status = "failed"
        run.failureReason = reason.trim().take(500)
        run.finishedAt = Instant.now(clock)
        return runs.save(run)
    }

    fun listRecent(guildId: Long): List<MultiResponseRunEntity> = runs.findTop20ByGuildIdOrderByStartedAtDesc(guildId)

    fun runDetail(runId: Long): MultiResponseRunDetail {
        val run = runs.findById(runId).orElseThrow { IllegalArgumentException("run not found: $runId") }
        val runCandidates = candidates.findByRunId(runId)
        return MultiResponseRunDetail(
            run = run,
            candidates = runCandidates,
            synthesis = syntheses.findByRunId(runId),
            policy = run.policyId?.let { policies.findById(it).orElse(null) },
            safetySummary = summarizeSafety(runCandidates),
            qualitySummary = summarizeQuality(runCandidates),
        )
    }

    fun providerFanoutLoad(guildId: Long): List<ProviderFanoutLoadSummary> {
        val recentRuns = runs.findTop20ByGuildIdOrderByStartedAtDesc(guildId)
        val runIds = recentRuns.map { it.id }.toSet()
        if (runIds.isEmpty()) return emptyList()
        val runCandidates = recentRuns.flatMap { candidates.findByRunId(it.id) }
        return runCandidates
            .filter { it.providerUserId != null }
            .groupBy { it.providerUserId!! }
            .map { (providerUserId, providerCandidates) ->
                val latencyValues = providerCandidates.mapNotNull { it.latencyMs }
                val timeoutCount = providerCandidates.count { it.status.equals("timeout", ignoreCase = true) }
                val failedCount =
                    providerCandidates.count {
                        it.status.equals("failed", ignoreCase = true) || it.status.equals("rejected", ignoreCase = true)
                    }
                val completedCount = providerCandidates.count { it.status.equals("completed", ignoreCase = true) }
                ProviderFanoutLoadSummary(
                    guildId = guildId,
                    providerUserId = providerUserId,
                    candidateCount = providerCandidates.size,
                    completedCount = completedCount,
                    timeoutCount = timeoutCount,
                    failedCount = failedCount,
                    averageLatencyMs = latencyValues.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                    averageQualityScore = providerCandidates.mapNotNull { it.qualityScore }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                    loadRisk = fanoutLoadRisk(providerCandidates.size, timeoutCount, failedCount, latencyValues),
                    runIds = providerCandidates.map { it.runId }.filter { it in runIds }.distinct(),
                )
            }.sortedWith(
                compareByDescending<ProviderFanoutLoadSummary> { riskRank(it.loadRisk) }
                    .thenByDescending { it.candidateCount }
                    .thenBy { it.providerUserId },
            )
    }

    fun decisionSummary(
        guildId: Long,
        channelId: Long? = null,
        limit: Int = 20,
    ): MultiResponseDecisionSummary {
        val recent =
            runs
                .findTop20ByGuildIdOrderByStartedAtDesc(guildId)
                .filter { channelId == null || it.channelId == channelId }
        val runCandidates = recent.flatMap { run -> candidates.findByRunId(run.id).map { run to it } }
        val synthesesByRunId = recent.mapNotNull { run -> syntheses.findByRunId(run.id)?.let { run.id to it } }.toMap()
        val completedRuns = recent.count { it.status.equals("completed", ignoreCase = true) }
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
        val timeoutCandidates = runCandidates.count { it.second.status.equals("timeout", ignoreCase = true) }
        val rejectedCandidates =
            runCandidates.count { (_, candidate) ->
                candidate.status.equals("failed", ignoreCase = true) ||
                    candidate.status.equals("rejected", ignoreCase = true) ||
                    candidate.hasBlockingSafetyFlag()
            }
        val qualityScores = runCandidates.mapNotNull { it.second.qualityScore }
        val statusCounts = runCandidates.groupingBy { it.second.status.ifBlank { "unknown" } }.eachCount()
        val completedCandidates = runCandidates.count { it.second.status.equals("completed", ignoreCase = true) }
        val adoptionRate = if (completedCandidates == 0) 0.0 else acceptedCandidates.toDouble() / completedCandidates
        val timeoutRate = if (runCandidates.isEmpty()) 0.0 else timeoutCandidates.toDouble() / runCandidates.size
        val averageQualityScore = qualityScores.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val riskCodes = mutableListOf<String>()
        val nextActions = mutableListOf<String>()
        if (recent.isEmpty()) {
            riskCodes += "no_recent_runs"
            nextActions += "다중 응답 실행 이력이 쌓이면 후보 선택 품질을 분석할 수 있어요."
        }
        if (runCandidates.isEmpty() && recent.isNotEmpty()) {
            riskCodes += "no_candidates"
            nextActions += "온라인 Provider와 fan-out 참여 태그를 확인하세요."
        }
        if (timeoutRate >= 0.25) {
            riskCodes += "high_timeout_rate"
            nextActions += "응답 속도/품질 모드를 낮추거나 과부하 Provider를 보호하세요."
        }
        if (qualityScores.isNotEmpty() && averageQualityScore < 60.0) {
            riskCodes += "low_quality"
            nextActions += "채널 AI 프롬프트, 지식 베이스, 모델 정책을 점검하세요."
        }
        if (completedRuns > 0 && acceptedCandidates == 0) {
            riskCodes += "no_selected_candidate"
            nextActions += "완료된 실행에 선택 후보나 합성 결과가 기록되는지 확인하세요."
        }
        if (recent.any { it.ragContextStatus?.startsWith("fallback") == true }) {
            riskCodes += "rag_context_fallback"
            nextActions += "채널 지식 베이스 색인 상태와 검색 범위를 점검하세요."
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
                                status = run.status,
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
                                status = candidate.status,
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

    fun pseudoStreamPlan(
        answer: String,
        requestedSteps: List<Int> = emptyList(),
        maxDiscordChars: Int = DISCORD_MESSAGE_SAFE_LIMIT,
    ): PseudoStreamPlan {
        featureGate.requireMultiResponseEnabled()
        val normalized = answer.trim()
        if (normalized.isBlank()) {
            return PseudoStreamPlan(
                finalLength = 0,
                truncated = false,
                editIntervalMs = PSEUDO_STREAM_EDIT_INTERVAL_MS,
                snapshots = emptyList(),
                warning = "empty_answer",
            )
        }
        val limit = maxDiscordChars.coerceIn(100, DISCORD_MESSAGE_SAFE_LIMIT)
        val visibleAnswer = normalized.take(limit)
        val truncated = normalized.length > visibleAnswer.length
        val steps = normalizePseudoStreamSteps(requestedSteps)
        val snapshots =
            steps
                .mapIndexed { index, percent ->
                    val length =
                        if (index == steps.lastIndex) {
                            visibleAnswer.length
                        } else {
                            ((visibleAnswer.length * percent) / 100).coerceIn(1, visibleAnswer.length)
                        }
                    PseudoStreamSnapshot(
                        sequence = index + 1,
                        percent = percent,
                        content = visibleAnswer.take(length),
                        charCount = length,
                        final = index == steps.lastIndex,
                    )
                }.dedupeSnapshots()
        return PseudoStreamPlan(
            finalLength = visibleAnswer.length,
            truncated = truncated,
            editIntervalMs = PSEUDO_STREAM_EDIT_INTERVAL_MS,
            snapshots = snapshots,
            warning = if (truncated) "discord_message_truncated_to_$limit" else null,
        )
    }

    fun dailyStats(guildId: Long): MultiResponseDailyStats {
        val recent = runs.findTop20ByGuildIdOrderByStartedAtDesc(guildId)
        val completed = recent.count { it.status == "completed" }
        val fallback = recent.count { it.status in setOf("no_provider", "blocked_sensitive", "failed") }
        val timeoutCandidates =
            recent
                .flatMap { candidates.findByRunId(it.id) }
                .count { it.status.equals("timeout", ignoreCase = true) }
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

    private fun normalizePseudoStreamSteps(requestedSteps: List<Int>): List<Int> {
        val normalized =
            requestedSteps
                .ifEmpty { listOf(33, 66, 100) }
                .map { it.coerceIn(1, 100) }
                .distinct()
                .sorted()
                .filter { it > 0 }
                .toMutableList()
        if (normalized.isEmpty() || normalized.last() != 100) normalized += 100
        return normalized
    }

    private fun List<PseudoStreamSnapshot>.dedupeSnapshots(): List<PseudoStreamSnapshot> {
        val deduped = mutableListOf<PseudoStreamSnapshot>()
        forEach { snapshot ->
            if (deduped.lastOrNull()?.content == snapshot.content && !snapshot.final) return@forEach
            deduped += snapshot.copy(sequence = deduped.size + 1)
        }
        val last = deduped.lastOrNull() ?: return emptyList()
        return if (last.final) deduped else deduped.dropLast(1) + last.copy(final = true, percent = 100)
    }

    private fun fanoutLoadRisk(
        candidateCount: Int,
        timeoutCount: Int,
        failedCount: Int,
        latencyValues: List<Int>,
    ): String {
        val timeoutRate = if (candidateCount == 0) 0.0 else timeoutCount.toDouble() / candidateCount
        val failureRate = if (candidateCount == 0) 0.0 else (timeoutCount + failedCount).toDouble() / candidateCount
        val averageLatency = latencyValues.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        return when {
            timeoutRate >= 0.5 || failureRate >= 0.75 -> "critical"
            timeoutRate >= 0.25 || failureRate >= 0.4 || averageLatency >= 10_000 -> "high"
            candidateCount >= 5 || averageLatency >= 5_000 -> "watch"
            else -> "normal"
        }
    }

    private fun riskRank(value: String): Int =
        when (value.lowercase()) {
            "critical" -> 4
            "high" -> 3
            "watch" -> 2
            else -> 1
        }

    private fun decisionReason(
        run: MultiResponseRunEntity,
        candidate: CandidateAnswerEntity,
        selected: Boolean,
    ): String =
        when {
            selected -> "selected_by_${syntheses.findByRunId(run.id)?.strategy ?: "run"}"
            candidate.hasBlockingSafetyFlag() -> "blocked_by_safety_flags"
            candidate.status.equals("timeout", ignoreCase = true) -> "candidate_timeout"
            candidate.status.equals("failed", ignoreCase = true) -> "candidate_failed"
            candidate.status.equals("rejected", ignoreCase = true) -> "candidate_rejected"
            candidate.status.equals("completed", ignoreCase = true) -> "completed_not_selected"
            else -> run.failureReason ?: "candidate_${candidate.status.ifBlank { "unknown" }}"
        }

    private fun String?.toIdSet(): Set<Long> =
        this
            .orEmpty()
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()

    private fun selectProviders(
        guildId: Long,
        policy: MultiResponsePolicyEntity,
        maxCandidates: Int = policy.maxCandidates,
        fanoutAllowed: Boolean = true,
    ): List<ProviderCapabilityProfileEntity> {
        val providers = providerCapabilities.findByGuildId(guildId)
        if (providers.any { it.overloadRisk.equals("critical", ignoreCase = true) }) return emptyList()
        val advancedFanout = policy.maxCandidates > 1 || !policy.mode.equals("single", ignoreCase = true) || policy.synthesisEnabled
        val effectiveMaxCandidates = if (fanoutAllowed) maxCandidates else 1
        val ranked =
            providers
                .filter { it.providerState.equals("ONLINE", ignoreCase = true) }
                .filter { !it.overloadRisk.equals("high", ignoreCase = true) && !it.overloadRisk.equals("critical", ignoreCase = true) }
                .filter { policy.providerDailyLimit <= 0 || it.dailyLimit <= 0 || it.dailyLimit >= policy.providerDailyLimit }
                .filter { !advancedFanout || it.hasFanoutOptIn() }
                .sortedWith(
                    compareByDescending<ProviderCapabilityProfileEntity> { it.qualityTier == "specialized" }
                        .thenByDescending { it.qualityTier == "high" }
                        .thenByDescending { it.modelCount }
                        .thenBy { it.providerUserId },
                )
        val selected = mutableListOf<ProviderCapabilityProfileEntity>()
        val usedModels = mutableSetOf<String>()
        for (provider in ranked) {
            val model = provider.firstModel()?.lowercase().orEmpty()
            if (policy.requireDistinctModels && model.isNotBlank() && !usedModels.add(model)) continue
            selected += provider
            if (selected.size >= effectiveMaxCandidates) break
        }
        return selected
    }

    private fun applyRagContextSnapshot(
        run: MultiResponseRunEntity,
        promptPreview: String?,
        responseMode: String,
    ) {
        val search = knowledgeSearch
        if (search == null) {
            run.ragContextStatus = "skipped_no_rag_service"
            return
        }
        val query = promptPreview?.trim()
        if (query.isNullOrBlank()) {
            run.ragContextStatus = "skipped_no_prompt"
            return
        }
        val plan =
            runCatching {
                search.contextPlan(
                    guildId = run.guildId,
                    query = query,
                    responseMode = responseMode,
                    channelId = run.channelId,
                )
            }.getOrElse { error ->
                run.ragContextStatus = "fallback:${error.javaClass.simpleName}"
                return
            }
        run.ragContextStatus =
            when {
                plan.enabled -> "ready"
                plan.fallbackReason != null -> "fallback:${plan.fallbackReason}"
                else -> "fallback:no_context"
            }
        run.ragContextSourceIds = plan.entries.joinToString(",") { it.sourceId.toString() }.ifBlank { null }
        run.ragContextChars = plan.usedChars
    }

    private fun CandidateAnswerEntity.hasBlockingSafetyFlag(): Boolean =
        safetyFlags
            .orEmpty()
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .any { it in BLOCKING_SAFETY_FLAGS }

    private fun failureSummary(runCandidates: List<CandidateAnswerEntity>): String {
        if (runCandidates.isEmpty()) return "multi-response failed: no candidates were planned"
        val statuses = runCandidates.groupingBy { it.status.ifBlank { "unknown" } }.eachCount()
        return "multi-response failed: no successful candidate; statuses=$statuses".take(500)
    }

    private fun ProviderCapabilityProfileEntity.hasFanoutOptIn(): Boolean {
        val tags =
            capabilityTags
                .orEmpty()
                .split(",", " ")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
        return tags.any { it in FANOUT_OPT_IN_TAGS }
    }

    private fun ProviderCapabilityProfileEntity.firstModel(): String? =
        modelNames
            .orEmpty()
            .split(",")
            .firstOrNull { it.isNotBlank() }
            ?.trim()

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

    private fun sanitizeText(
        value: String?,
        maxLength: Int,
    ): String? =
        value
            ?.trim()
            ?.replace(SECRET_PATTERN, "[redacted]")
            ?.take(maxLength)
            ?.ifBlank { null }

    private fun String?.isSensitivePrompt(): Boolean {
        val text = this?.trim().orEmpty()
        if (text.isBlank()) return false
        return SENSITIVE_PROMPT_PATTERNS.any { it.containsMatchIn(text) }
    }

    private fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "")

    private companion object {
        val FANOUT_OPT_IN_TAGS = setOf("multi-response", "multi_response", "fanout", "fanout-opt-in")
        val FALLBACK_RUN_STATUSES = setOf("no_provider", "blocked_sensitive", "failed")
        val BLOCKING_SAFETY_FLAGS = setOf("unsafe", "policy_violation", "sensitive", "blocked", "jailbreak")
        const val DISCORD_MESSAGE_SAFE_LIMIT = 1_900
        const val PSEUDO_STREAM_EDIT_INTERVAL_MS = 1_200
        val SECRET_PATTERN = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")
        val SENSITIVE_PROMPT_PATTERNS =
            listOf(
                Regex("""(?i)\b(password|passwd|pwd|secret)\b"""),
                Regex("(?i)(api[_-]?key|bot[_-]?token|discord[_-]?bot[_-]?token|private[_-]?key|access[_-]?token)"),
                Regex("(?i)-----BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----"),
                Regex("(?i)sk-[A-Za-z0-9_-]{20,}"),
            )
    }
}

data class MultiResponseRunDetail(
    val run: MultiResponseRunEntity,
    val candidates: List<CandidateAnswerEntity>,
    val synthesis: SynthesisResultEntity?,
    val policy: MultiResponsePolicyEntity?,
    val safetySummary: String,
    val qualitySummary: String,
)

data class MultiResponseDailyStats(
    val guildId: Long,
    val recentRunCount: Int,
    val completedRunCount: Int,
    val fallbackRunCount: Int,
    val timeoutCandidateCount: Int,
    val averageActualFanout: Double,
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
)

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
    val run: MultiResponseRunEntity,
    val synthesis: SynthesisResultEntity?,
    val fallbackReason: String?,
)

data class CandidateAdoptionResult(
    val run: MultiResponseRunEntity,
    val candidate: CandidateAnswerEntity,
    val synthesis: SynthesisResultEntity,
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
