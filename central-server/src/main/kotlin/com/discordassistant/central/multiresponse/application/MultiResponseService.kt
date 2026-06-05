package com.discordassistant.central.multiresponse.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.ainetwork.application.ProviderSafetyService
import com.discordassistant.central.ainetwork.domain.model.FeedbackStatus
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.knowledge.application.KnowledgeSafety
import com.discordassistant.central.knowledge.application.KnowledgeSearchService
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponseRunEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponseRunRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.SynthesisResultEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.SynthesisResultRepository
import com.discordassistant.central.multiresponse.domain.model.CandidateStatus
import com.discordassistant.central.multiresponse.domain.model.MultiResponseRunStatus
import com.discordassistant.central.multiresponse.domain.model.SynthesisStatus
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.shared.ContentSafety.BLOCKING_SAFETY_FLAGS
import com.discordassistant.central.shared.ModelQualityTier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
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
    private val connectionRegistry: ConnectionRegistry? = null,
    private val reporting: MultiResponseReportingService =
        MultiResponseReportingService(
            policies = policies,
            runs = runs,
            candidates = candidates,
            syntheses = syntheses,
            featureGate = featureGate,
        ),
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
        disabledReason: String? = null,
    ): MultiResponsePolicyView =
        savePolicyEntity(
            guildId = guildId,
            channelId = channelId,
            channelAiId = channelAiId,
            mode = mode,
            maxCandidates = maxCandidates,
            requireDistinctModels = requireDistinctModels,
            providerDailyLimit = providerDailyLimit,
            timeoutSeconds = timeoutSeconds,
            synthesisEnabled = synthesisEnabled,
            disabledReason = disabledReason,
        ).toView()

    @Transactional
    fun savePolicyEntity(
        guildId: Long,
        channelId: Long?,
        channelAiId: Long?,
        mode: String,
        maxCandidates: Int,
        requireDistinctModels: Boolean,
        providerDailyLimit: Int,
        timeoutSeconds: Int,
        synthesisEnabled: Boolean,
        disabledReason: String? = null,
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
        entity.mode =
            com.discordassistant.central.multiresponse.domain.model.MultiResponseMode
                .fromWire(mode)
                .wire
        entity.maxCandidates = maxCandidates.coerceIn(1, featureGate.multiResponseMaxFanout())
        entity.requireDistinctModels = requireDistinctModels
        entity.providerDailyLimit = providerDailyLimit.coerceAtLeast(0)
        entity.timeoutSeconds = timeoutSeconds.coerceIn(10, 300)
        entity.disabledReason =
            sanitizeDisabledReason(disabledReason)
                .takeUnless { it.isNullOrBlank() }
                ?: disabledReasonForMode(entity.mode)
        if (entity.isDisabled()) {
            entity.maxCandidates = 1
            entity.synthesisEnabled = false
        } else {
            entity.synthesisEnabled = synthesisEnabled && featureGate.snapshot().multiResponseSynthesis && entity.maxCandidates > 1
        }
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
    ): MultiResponseRunView = startRunEntity(guildId, channelId, requestId, promptPreview, responseMode).toView()

    @Transactional
    fun startRunEntity(
        guildId: Long,
        channelId: Long,
        requestId: String = newRequestId(),
        promptPreview: String? = null,
        responseMode: String = "balanced",
    ): MultiResponseRunEntity {
        featureGate.requireMultiResponseEnabled()
        val guildPolicy = policies.findByGuildIdAndChannelIdIsNull(guildId)
        val channelPolicy = policies.findByGuildIdAndChannelId(guildId, channelId)
        val disabledPolicy = disabledPolicy(guildPolicy, channelPolicy)
        val policy =
            channelPolicy
                ?: guildPolicy
                ?: savePolicyEntity(
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
                    requestId = sanitizeRequestId(requestId),
                    policyId = disabledPolicy?.id ?: policy.id,
                    status = MultiResponseRunStatus.PLANNED,
                    startedAt = Instant.now(clock),
                ),
            )
        disabledPolicy?.let { return saveDisabledRun(run, it) }
        if (promptPreview.isSensitivePrompt()) {
            run.transitionTo(MultiResponseRunStatus.BLOCKED_SENSITIVE)
            run.failureReason = "multi-response fan-out disabled for sensitive-looking prompt"
            run.ragContextStatus = "skipped_sensitive_prompt"
            run.finishedAt = Instant.now(clock)
            return runs.save(run)
        }
        applyRagContextSnapshot(run, promptPreview, responseMode)
        val executionPlan = safety?.executionPlan(guildId, policy.mode, policy.maxCandidates)
        if (executionPlan != null && executionPlan.maxSafeCandidates == 0) {
            run.transitionTo(MultiResponseRunStatus.NO_PROVIDER)
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
                    status = CandidateStatus.PLANNED,
                    createdAt = Instant.now(clock),
                ),
            )
        }
        run.candidateCount = selectedProviders.size
        run.transitionTo(if (selectedProviders.isEmpty()) MultiResponseRunStatus.NO_PROVIDER else MultiResponseRunStatus.RUNNING)
        return runs.save(run)
    }

    @Transactional
    fun startRuntimeObservation(
        guildId: Long,
        channelId: Long,
        requestId: String = newRequestId(),
        promptPreview: String? = null,
        responseMode: String = "balanced",
        maxCandidates: Int = 1,
    ): MultiResponseRunEntity {
        featureGate.requireMultiResponseEnabled()
        val guildPolicy = policies.findByGuildIdAndChannelIdIsNull(guildId)
        val channelPolicy = policies.findByGuildIdAndChannelId(guildId, channelId)
        val disabledPolicy = disabledPolicy(guildPolicy, channelPolicy)
        val savedPolicy = channelPolicy ?: guildPolicy
        val effectiveMaxCandidates = maxCandidates.coerceIn(1, featureGate.multiResponseMaxFanout())
        val runtimePolicy =
            savedPolicy
                ?: MultiResponsePolicyEntity(
                    guildId = guildId,
                    channelId = channelId,
                    mode = runtimeObservationMode(responseMode, effectiveMaxCandidates),
                    maxCandidates = effectiveMaxCandidates,
                    requireDistinctModels = false,
                    providerDailyLimit = 0,
                    timeoutSeconds = 120,
                    synthesisEnabled = effectiveMaxCandidates > 1 && featureGate.snapshot().multiResponseSynthesis,
                    createdAt = Instant.now(clock),
                    updatedAt = Instant.now(clock),
                )
        val run =
            runs.save(
                MultiResponseRunEntity(
                    guildId = guildId,
                    channelId = channelId,
                    requestId = sanitizeRequestId(requestId),
                    policyId = disabledPolicy?.id ?: savedPolicy?.id,
                    status = MultiResponseRunStatus.PLANNED,
                    startedAt = Instant.now(clock),
                ),
            )
        disabledPolicy?.let { return saveDisabledRun(run, it) }
        if (promptPreview.isSensitivePrompt()) {
            run.transitionTo(MultiResponseRunStatus.BLOCKED_SENSITIVE)
            run.failureReason = "multi-response fan-out disabled for sensitive-looking prompt"
            run.ragContextStatus = "skipped_sensitive_prompt"
            run.finishedAt = Instant.now(clock)
            return runs.save(run)
        }
        applyRagContextSnapshot(run, promptPreview, responseMode)
        val executionPlan = safety?.executionPlan(guildId, runtimePolicy.mode, runtimePolicy.maxCandidates)
        if (executionPlan != null && executionPlan.maxSafeCandidates == 0) {
            run.transitionTo(MultiResponseRunStatus.NO_PROVIDER)
            run.failureReason = executionPlan.reasons.joinToString(" ")
            run.finishedAt = Instant.now(clock)
            return runs.save(run)
        }
        val selectedProviders =
            selectProviders(
                guildId = guildId,
                policy = runtimePolicy,
                maxCandidates = executionPlan?.maxSafeCandidates ?: runtimePolicy.maxCandidates,
                fanoutAllowed = executionPlan?.fanoutAllowed ?: true,
            )
        selectedProviders.forEach { provider ->
            candidates.save(
                CandidateAnswerEntity(
                    runId = run.id,
                    providerUserId = provider.providerUserId,
                    modelName = provider.firstModel(),
                    status = CandidateStatus.PLANNED,
                    createdAt = Instant.now(clock),
                ),
            )
        }
        run.candidateCount = selectedProviders.size
        run.transitionTo(if (selectedProviders.isEmpty()) MultiResponseRunStatus.NO_PROVIDER else MultiResponseRunStatus.RUNNING)
        return runs.save(run)
    }

    @Transactional
    fun recordRuntimeSingleRouteResult(
        runId: Long,
        providerUserId: Long?,
        modelName: String?,
        answerRef: String?,
        completed: Boolean,
        latencyMs: Int?,
        failureReason: String?,
    ): MultiResponseCompletion {
        featureGate.requireMultiResponseEnabled()
        val run = runs.findById(runId).orElseThrow { IllegalArgumentException("run not found: $runId") }
        if (run.status == MultiResponseRunStatus.BLOCKED_SENSITIVE) {
            return MultiResponseCompletion(
                run = run.toView(),
                synthesis = syntheses.findByRunId(runId)?.toView(),
                fallbackReason = run.failureReason,
            )
        }
        val now = Instant.now(clock)
        val runCandidates = candidates.findByRunId(runId)
        val candidate =
            matchingRuntimeCandidate(runCandidates, providerUserId)
                ?: candidates.save(
                    CandidateAnswerEntity(
                        runId = runId,
                        providerUserId = providerUserId,
                        modelName = modelName,
                        status = CandidateStatus.PLANNED,
                        createdAt = now,
                    ),
                )
        candidate.modelName = modelName ?: candidate.modelName
        candidate.latencyMs = latencyMs
        candidate.answerRef = answerRef?.trim()?.ifBlank { null }
        candidate.status =
            if (completed && !candidate.answerRef.isNullOrBlank()) CandidateStatus.COMPLETED else CandidateStatus.FAILED
        candidate.safetyFlags = if (completed) "single_route" else null
        candidate.qualityScore = if (completed) 80 else null
        val savedCandidate = candidates.save(candidate)
        run.candidateCount = maxOf(run.candidateCount, candidates.findByRunId(runId).size)
        if (completed && !savedCandidate.answerRef.isNullOrBlank()) {
            val synthesis =
                synthesize(
                    runId = runId,
                    answerRef = savedCandidate.answerRef!!,
                    selectedCandidateIds = listOf(savedCandidate.id),
                    strategy = "single_route_runtime",
                    qualitySummary = "single route completed; fan-out observability attached",
                    safetySummary = "single_route",
                )
            val savedRun = runs.findById(runId).orElse(run)
            return MultiResponseCompletion(run = savedRun.toView(), synthesis = synthesis, fallbackReason = null)
        }
        run.transitionTo(
            when {
                run.status == MultiResponseRunStatus.NO_PROVIDER -> MultiResponseRunStatus.NO_PROVIDER
                else -> MultiResponseRunStatus.FAILED
            },
        )
        run.failureReason = failureReason?.trim()?.take(500) ?: run.failureReason ?: "single route failed"
        run.finishedAt = now
        val savedRun = runs.save(run)
        return MultiResponseCompletion(run = savedRun.toView(), synthesis = null, fallbackReason = savedRun.failureReason)
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
    ): CandidateAnswerView {
        featureGate.requireMultiResponseEnabled()
        val candidate =
            candidates.findByRunIdAndId(runId, candidateId)
                ?: throw IllegalArgumentException("candidate not found: run=$runId candidate=$candidateId")
        candidate.answerRef = answerRef
        candidate.status = CandidateStatus.fromWire(status.trim().ifBlank { "completed" })
        candidate.latencyMs = latencyMs
        candidate.safetyFlags = safetyFlags.joinToString(",").ifBlank { null }
        candidate.qualityScore = qualityScore
        return candidates.save(candidate).toView()
    }

    @Transactional
    fun synthesize(
        runId: Long,
        answerRef: String,
        selectedCandidateIds: List<Long>,
        strategy: String = "best_by_heuristic",
        qualitySummary: String? = null,
        safetySummary: String? = null,
    ): SynthesisResultView {
        featureGate.requireMultiResponseEnabled()
        if (!synthesisAllowed(strategy, selectedCandidateIds)) {
            featureGate.requireMultiResponseSynthesisEnabled()
        }
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
        synthesis.status = SynthesisStatus.COMPLETED
        synthesis.selectedCandidateIds = selectedCandidateIds.joinToString(",")
        synthesis.strategy = strategy.trim().ifBlank { "best_by_heuristic" }.take(80)
        synthesis.qualitySummary = qualitySummary?.trim()?.take(1000)?.ifBlank { null } ?: summarizeQuality(runCandidates)
        synthesis.safetySummary = safetySummary?.trim()?.take(1000)?.ifBlank { null } ?: summarizeSafety(runCandidates)
        val saved = syntheses.save(synthesis)
        run.transitionTo(MultiResponseRunStatus.COMPLETED)
        run.selectedCandidateId = selectedCandidateIds.firstOrNull()
        run.finishedAt = now
        runs.save(run)
        return saved.toView()
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
                .filter { it.status == CandidateStatus.COMPLETED }
                .filter { !it.answerRef.isNullOrBlank() }
                .filter { !it.hasBlockingSafetyFlag() }
                .sortedWith(
                    compareByDescending<CandidateAnswerEntity> { it.qualityScore ?: Int.MIN_VALUE }
                        .thenBy { it.latencyMs ?: Int.MAX_VALUE }
                        .thenBy { it.id },
                )
        val best = successful.firstOrNull()
        if (best == null) {
            run.transitionTo(MultiResponseRunStatus.FAILED)
            run.failureReason = failureSummary(runCandidates)
            run.finishedAt = Instant.now(clock)
            runs.save(run)
            return MultiResponseCompletion(run = run.toView(), synthesis = null, fallbackReason = run.failureReason)
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
        return MultiResponseCompletion(run = savedRun.toView(), synthesis = synthesis, fallbackReason = null)
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
        require(candidate.status == CandidateStatus.COMPLETED) { "only completed candidates can be adopted" }
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
        synthesis.status = SynthesisStatus.COMPLETED
        synthesis.selectedCandidateIds = candidate.id.toString()
        synthesis.strategy = "user_selected_candidate"
        synthesis.qualitySummary = "user selected candidate #${candidate.id}"
        synthesis.safetySummary = summarizeSafety(listOf(candidate))
        val savedSynthesis = syntheses.save(synthesis)
        run.selectedCandidateId = candidate.id
        run.transitionTo(MultiResponseRunStatus.COMPLETED)
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
                    status = FeedbackStatus.OPEN,
                    createdAt = now,
                ),
            )
        return CandidateAdoptionResult(
            run = savedRun.toView(),
            candidate = candidate.toView(),
            synthesis = savedSynthesis.toView(),
            feedbackId = feedback?.id,
        )
    }

    @Transactional
    fun failRun(
        runId: Long,
        reason: String,
    ): MultiResponseRunView {
        featureGate.requireMultiResponseEnabled()
        val run = runs.findById(runId).orElseThrow { IllegalArgumentException("run not found: $runId") }
        run.transitionTo(MultiResponseRunStatus.FAILED)
        run.failureReason = reason.trim().take(500)
        run.finishedAt = Instant.now(clock)
        return runs.save(run).toView()
    }

    fun listRecent(guildId: Long): List<MultiResponseRunView> = reporting.listRecent(guildId)

    fun runDetail(runId: Long): MultiResponseRunDetail = reporting.runDetail(runId)

    fun providerFanoutLoad(guildId: Long): List<ProviderFanoutLoadSummary> = reporting.providerFanoutLoad(guildId)

    fun decisionSummary(
        guildId: Long,
        channelId: Long? = null,
        limit: Int = 20,
    ): MultiResponseDecisionSummary = reporting.decisionSummary(guildId, channelId, limit)

    fun operationsSummary(
        guildId: Long,
        channelId: Long? = null,
    ): MultiResponseOperationsSummary = reporting.operationsSummary(guildId, channelId)

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

    fun dailyStats(guildId: Long): MultiResponseDailyStats = reporting.dailyStats(guildId)

    fun recommendFanout(
        guildId: Long,
        channelId: Long? = null,
        responseMode: String = "balanced",
        requestedCandidates: Int = 1,
    ): MultiResponseFanoutRecommendation {
        featureGate.requireMultiResponseDashboardEnabled()
        val guildPolicy = policies.findByGuildIdAndChannelIdIsNull(guildId)
        val channelPolicy = channelId?.let { policies.findByGuildIdAndChannelId(guildId, it) }
        val disabledPolicy = disabledPolicy(guildPolicy, channelPolicy)
        val policySource =
            when {
                channelPolicy != null -> "channel"
                guildPolicy != null -> "guild"
                else -> "default"
            }
        val policy =
            channelPolicy
                ?: guildPolicy
                ?: MultiResponsePolicyEntity(
                    guildId = guildId,
                    channelId = channelId,
                    mode =
                        com.discordassistant.central.multiresponse.domain.model.MultiResponseMode
                            .fromWire(responseMode)
                            .wire,
                    maxCandidates = requestedCandidates.coerceIn(1, featureGate.multiResponseMaxFanout()),
                    createdAt = Instant.now(clock),
                    updatedAt = Instant.now(clock),
                )
        if (disabledPolicy != null) {
            return MultiResponseFanoutRecommendation.disabled(
                guildId = guildId,
                channelId = channelId,
                policySource = policySource,
                policyMode = disabledPolicy.mode,
                reason = disabledPolicy.disabledMessage(),
            )
        }
        val executionPlan = safety?.executionPlan(guildId, policy.mode, policy.maxCandidates)
        val maxSafeCandidates = executionPlan?.maxSafeCandidates ?: policy.maxCandidates
        if (maxSafeCandidates <= 0) {
            return MultiResponseFanoutRecommendation(
                guildId = guildId,
                channelId = channelId,
                policySource = policySource,
                policyMode = policy.mode,
                requestedCandidates = policy.maxCandidates,
                maxSafeCandidates = 0,
                recommendedCandidateCount = 0,
                fanoutAllowed = false,
                status = "blocked_provider_safety",
                reasons = executionPlan?.reasons?.takeIf { it.isNotEmpty() } ?: listOf("provider_safety_blocked"),
                providers = emptyList(),
            )
        }
        val selectedProviders =
            selectProviders(
                guildId = guildId,
                policy = policy,
                maxCandidates = maxSafeCandidates,
                fanoutAllowed = executionPlan?.fanoutAllowed ?: true,
            )
        val status =
            when {
                selectedProviders.isEmpty() -> "no_provider"
                selectedProviders.size > 1 -> "fanout_recommended"
                else -> "single_recommended"
            }
        return MultiResponseFanoutRecommendation(
            guildId = guildId,
            channelId = channelId,
            policySource = policySource,
            policyMode = policy.mode,
            requestedCandidates = policy.maxCandidates,
            maxSafeCandidates = maxSafeCandidates,
            recommendedCandidateCount = selectedProviders.size,
            fanoutAllowed = (executionPlan?.fanoutAllowed ?: true) && selectedProviders.size > 1,
            status = status,
            reasons = executionPlan?.reasons.orEmpty().ifEmpty { listOf(status) },
            providers =
                selectedProviders.map {
                    MultiResponseRecommendedProvider(
                        providerUserId = it.providerUserId,
                        modelName = it.firstModel(),
                        qualityTier = it.qualityTier.wire,
                        overloadRisk = it.overloadRisk.wire,
                    )
                },
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

    private fun disabledPolicy(
        guildPolicy: MultiResponsePolicyEntity?,
        channelPolicy: MultiResponsePolicyEntity?,
    ): MultiResponsePolicyEntity? =
        when {
            guildPolicy?.isDisabled() == true -> guildPolicy
            channelPolicy?.isDisabled() == true -> channelPolicy
            else -> null
        }

    private fun saveDisabledRun(
        run: MultiResponseRunEntity,
        policy: MultiResponsePolicyEntity,
    ): MultiResponseRunEntity {
        run.transitionTo(MultiResponseRunStatus.DISABLED_BY_POLICY)
        run.candidateCount = 0
        run.failureReason = policy.disabledMessage()
        run.ragContextStatus = "skipped_policy_disabled"
        run.finishedAt = Instant.now(clock)
        return runs.save(run)
    }

    private fun MultiResponsePolicyEntity.isDisabled(): Boolean =
        mode.trim().lowercase() in DISABLED_POLICY_MODES || !disabledReason.isNullOrBlank()

    private fun MultiResponsePolicyEntity.disabledMessage(): String {
        val scope = if (channelId == null) "guild" else "channel"
        val reason = disabledReason?.trim()?.takeIf { it.isNotBlank() } ?: "policy_disabled"
        return "multi-response disabled by $scope policy: $reason".take(500)
    }

    private fun disabledReasonForMode(mode: String): String? =
        mode.takeIf { it.trim().lowercase() in DISABLED_POLICY_MODES }?.let { "policy_disabled" }

    private fun sanitizeDisabledReason(reason: String?): String? = reason?.trim()?.take(500)

    private fun selectProviders(
        guildId: Long,
        policy: MultiResponsePolicyEntity,
        maxCandidates: Int = policy.maxCandidates,
        fanoutAllowed: Boolean = true,
    ): List<ProviderCapabilityProfileEntity> {
        val providers = providerCapabilities.findByGuildId(guildId)
        if (providers.any { it.overloadRisk == OverloadRisk.CRITICAL }) return emptyList()
        val effectiveMaxCandidates = if (fanoutAllowed) maxCandidates.coerceIn(1, featureGate.multiResponseMaxFanout()) else 1
        val advancedFanout = effectiveMaxCandidates > 1 || policy.synthesisEnabled
        val ranked =
            providers
                .filter { it.providerState == ProviderAvailability.ONLINE }
                .filter { it.hasLiveCapacity(guildId) }
                .filter { !it.overloadRisk.isOverload }
                .filter { policy.providerDailyLimit <= 0 || it.dailyLimit <= 0 || it.dailyLimit >= policy.providerDailyLimit }
                .filter { !advancedFanout || !it.hasFanoutExclusion() }
                .filter { !advancedFanout || it.hasFanoutOptIn() }
                .sortedWith(
                    compareByDescending<ProviderCapabilityProfileEntity> { it.qualityTier == ModelQualityTier.SPECIALIZED }
                        .thenByDescending { it.qualityTier == ModelQualityTier.HIGH }
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

    private fun ProviderCapabilityProfileEntity.hasLiveCapacity(guildId: Long): Boolean {
        if (maxConcurrency <= 0) return false
        val session = connectionRegistry?.byProvider(guildId, providerUserId) ?: return true
        if (session.remainingDailyRequests <= 0) return false
        val liveCap = session.capability.maxConcurrency.coerceAtLeast(1)
        val profileCap = maxConcurrency.coerceAtLeast(1)
        return session.activeRequests < minOf(liveCap, profileCap)
    }

    private fun matchingRuntimeCandidate(
        runCandidates: List<CandidateAnswerEntity>,
        providerUserId: Long?,
    ): CandidateAnswerEntity? =
        providerUserId
            ?.let { provider -> runCandidates.firstOrNull { it.providerUserId == provider } }
            ?: runCandidates.firstOrNull()

    private fun runtimeObservationMode(
        responseMode: String,
        maxCandidates: Int,
    ): String =
        when {
            maxCandidates > 1 -> "compare"
            responseMode.equals("deep", ignoreCase = true) -> "deep"
            responseMode.equals("saving", ignoreCase = true) -> "saving"
            responseMode.equals("fast", ignoreCase = true) -> "fast"
            else -> "single"
        }

    private fun applyRagContextSnapshot(
        run: MultiResponseRunEntity,
        promptPreview: String?,
        responseMode: String,
    ) {
        if (!featureGate.canUseMultiResponseRag()) {
            run.ragContextStatus = "skipped_feature_disabled"
            return
        }
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
        val statuses = runCandidates.groupingBy { it.status.wire }.eachCount()
        return "multi-response failed: no successful candidate; statuses=$statuses".take(500)
    }

    private fun synthesisAllowed(
        strategy: String,
        selectedCandidateIds: List<Long>,
    ): Boolean =
        featureGate.snapshot().multiResponseSynthesis ||
            selectedCandidateIds.size <= 1 &&
            strategy.trim().lowercase() in SYNTHESIS_FLAG_SAFE_SELECTION_STRATEGIES

    private fun ProviderCapabilityProfileEntity.hasFanoutExclusion(): Boolean {
        val tags =
            capabilityTags
                .orEmpty()
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
        return tags.any { it in FANOUT_EXCLUSION_TAGS }
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

    private fun sanitizeRequestId(requestId: String): String {
        val trimmed = requestId.trim().ifBlank { newRequestId() }
        if (trimmed.hasSensitiveMaterial()) return "redacted-${sha256(trimmed).take(12)}"
        return trimmed.take(160)
    }

    private fun String.hasSensitiveMaterial(): Boolean =
        KnowledgeSafety.containsSensitiveMaterial(this) || SECRET_PATTERN.containsMatchIn(this)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "")

    private companion object {
        val FANOUT_OPT_IN_TAGS = setOf("multi-response", "multi_response", "fanout", "fanout-opt-in")
        val FANOUT_EXCLUSION_TAGS = setOf("fanout-excluded", "fanout-opt-out", "no-fanout", "multi-response-excluded")
        val DISABLED_POLICY_MODES = setOf("disabled", "off", "kill_switch", "kill-switch")
        val SYNTHESIS_FLAG_SAFE_SELECTION_STRATEGIES =
            setOf("single_route_runtime", "best_successful_candidate", "best_by_heuristic")
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

internal fun MultiResponseRunEntity.toView(): MultiResponseRunView =
    MultiResponseRunView(
        id = id,
        guildId = guildId,
        channelId = channelId,
        requestId = requestId,
        policyId = policyId,
        status = status.wire,
        candidateCount = candidateCount,
        selectedCandidateId = selectedCandidateId,
        ragContextStatus = ragContextStatus,
        ragContextSourceIds = ragContextSourceIds,
        ragContextChars = ragContextChars,
        startedAt = startedAt,
        finishedAt = finishedAt,
        failureReason = failureReason,
    )

internal fun MultiResponsePolicyEntity.toView(): MultiResponsePolicyView =
    MultiResponsePolicyView(
        id = id,
        guildId = guildId,
        channelId = channelId,
        mode = mode,
        maxCandidates = maxCandidates,
        requireDistinctModels = requireDistinctModels,
        providerDailyLimit = providerDailyLimit,
        timeoutSeconds = timeoutSeconds,
        synthesisEnabled = synthesisEnabled,
        disabledReason = disabledReason,
    )

internal fun CandidateAnswerEntity.toView(): CandidateAnswerView =
    CandidateAnswerView(
        id = id,
        runId = runId,
        providerUserId = providerUserId,
        modelName = modelName,
        answerRef = answerRef,
        status = status.wire,
        latencyMs = latencyMs,
        safetyFlags = safetyFlags,
        qualityScore = qualityScore,
    )

internal fun SynthesisResultEntity.toView(): SynthesisResultView =
    SynthesisResultView(
        id = id,
        runId = runId,
        answerRef = answerRef,
        status = status.wire,
        selectedCandidateIds = selectedCandidateIds,
        strategy = strategy,
        qualitySummary = qualitySummary,
        safetySummary = safetySummary,
    )
