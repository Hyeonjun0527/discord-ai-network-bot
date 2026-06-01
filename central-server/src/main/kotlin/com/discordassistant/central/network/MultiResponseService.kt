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
    ): MultiResponseRunEntity {
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
        val selectedProviders = selectProviders(guildId, policy)
        selectedProviders.forEach { provider ->
            val firstModel =
                provider
                    .modelNames
                    .orEmpty()
                    .split(",")
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
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
    ): SynthesisResultEntity {
        val run = runs.findById(runId).orElseThrow { IllegalArgumentException("run not found: $runId") }
        val now = Instant.now(clock)
        val synthesis =
            syntheses.findByRunId(runId)
                ?: SynthesisResultEntity(runId = runId, createdAt = now)
        synthesis.answerRef = answerRef.trim()
        synthesis.status = "completed"
        synthesis.selectedCandidateIds = selectedCandidateIds.joinToString(",")
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
        val run = runs.findById(runId).orElseThrow { IllegalArgumentException("run not found: $runId") }
        run.status = "failed"
        run.failureReason = reason.trim().take(500)
        run.finishedAt = Instant.now(clock)
        return runs.save(run)
    }

    fun listRecent(guildId: Long): List<MultiResponseRunEntity> = runs.findTop20ByGuildIdOrderByStartedAtDesc(guildId)

    private fun selectProviders(
        guildId: Long,
        policy: MultiResponsePolicyEntity,
    ) = providerCapabilities
        .findByGuildId(guildId)
        .filter { it.providerState.equals("ONLINE", ignoreCase = true) }
        .filter { !it.overloadRisk.equals("high", ignoreCase = true) }
        .filter { policy.providerDailyLimit <= 0 || it.dailyLimit <= 0 || it.dailyLimit >= policy.providerDailyLimit }
        .sortedWith(
            compareByDescending<ProviderCapabilityProfileEntity> { it.qualityTier == "specialized" }
                .thenByDescending { it.qualityTier == "high" }
                .thenByDescending { it.modelCount }
                .thenBy { it.providerUserId },
        ).take(policy.maxCandidates)

    private fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "")
}
