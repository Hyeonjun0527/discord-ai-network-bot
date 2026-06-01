package com.discordassistant.central.network

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
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
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
            run.finishedAt = Instant.now(clock)
            return runs.save(run)
        }
        val selectedProviders = selectProviders(guildId, policy)
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

    private fun selectProviders(
        guildId: Long,
        policy: MultiResponsePolicyEntity,
    ): List<ProviderCapabilityProfileEntity> {
        val providers = providerCapabilities.findByGuildId(guildId)
        if (providers.any { it.overloadRisk.equals("critical", ignoreCase = true) }) return emptyList()
        val advancedFanout = policy.maxCandidates > 1 || !policy.mode.equals("single", ignoreCase = true) || policy.synthesisEnabled
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
            if (selected.size >= policy.maxCandidates) break
        }
        return selected
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

    private fun String?.isSensitivePrompt(): Boolean {
        val text = this?.trim().orEmpty()
        if (text.isBlank()) return false
        return SENSITIVE_PROMPT_PATTERNS.any { it.containsMatchIn(text) }
    }

    private fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "")

    private companion object {
        val FANOUT_OPT_IN_TAGS = setOf("multi-response", "multi_response", "fanout", "fanout-opt-in")
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
