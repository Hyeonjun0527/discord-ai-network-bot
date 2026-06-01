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
        entity.maxCandidates = maxCandidates.coerceIn(1, 5)
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
        applyRagContextSnapshot(run, promptPreview, responseMode)
        if (promptPreview.isSensitivePrompt()) {
            run.status = "blocked_sensitive"
            run.failureReason = "multi-response fan-out disabled for sensitive-looking prompt"
            run.finishedAt = Instant.now(clock)
            return runs.save(run)
        }
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
        val BLOCKING_SAFETY_FLAGS = setOf("unsafe", "policy_violation", "sensitive", "blocked", "jailbreak")
        val SECRET_PATTERN = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")
        val SENSITIVE_PROMPT_PATTERNS =
            listOf(
                Regex("(?i)\b(password|passwd|pwd|secret)\b"),
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
