package com.discordassistant.central.multiresponse.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.ainetwork.application.ProviderSafetyService
import com.discordassistant.central.ainetwork.domain.model.FeedbackStatus
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
    private val connectionRegistry: ConnectionRegistry? = null,
    private val reporting: MultiResponseReportingService =
        MultiResponseReportingService(
            policies = policies,
            runs = runs,
            candidates = candidates,
            syntheses = syntheses,
            featureGate = featureGate,
        ),
    private val promptSafety: PromptSafetyScrubber = PromptSafetyScrubber(),
    private val pseudoStreamPlanner: PseudoStreamPlanner = PseudoStreamPlanner(featureGate),
    private val candidateSummaries: CandidateSummaries = CandidateSummaries(),
    private val policyResolver: MultiResponsePolicyResolver = MultiResponsePolicyResolver(featureGate),
    private val providerSelector: ProviderFanoutSelector =
        ProviderFanoutSelector(
            providerCapabilities = providerCapabilities,
            featureGate = featureGate,
            connectionRegistry = connectionRegistry,
        ),
    private val fanoutPlanner: MultiResponseFanoutPlanner =
        MultiResponseFanoutPlanner(
            policies = policies,
            providerCapabilities = providerCapabilities,
            clock = clock,
            featureGate = featureGate,
            safety = safety,
            connectionRegistry = connectionRegistry,
            policyResolver = policyResolver,
            providerSelector = providerSelector,
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
        // savedPolicy 가 없으면 효과적 mode/maxCandidates 를 담은 runtime policy 를 **영속화**한다.
        // (startRunEntity 가 무정책 시 기본 policy 를 저장하는 것과 동일 패턴) 이렇게 해야 run.policyId 가
        // 실제 행을 가리켜 runDetail/decisionSummary 가 이 run 이 어떻게 구성됐는지 복원할 수 있다.
        val runtimePolicy =
            savedPolicy
                ?: policies.save(
                    MultiResponsePolicyEntity(
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
                    ),
                )
        val run =
            runs.save(
                MultiResponseRunEntity(
                    guildId = guildId,
                    channelId = channelId,
                    requestId = sanitizeRequestId(requestId),
                    policyId = disabledPolicy?.id ?: runtimePolicy.id,
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
        maxDiscordChars: Int = PseudoStreamPlanner.DISCORD_MESSAGE_SAFE_LIMIT,
    ): PseudoStreamPlan = pseudoStreamPlanner.pseudoStreamPlan(answer, requestedSteps, maxDiscordChars)

    fun dailyStats(guildId: Long): MultiResponseDailyStats = reporting.dailyStats(guildId)

    fun recommendFanout(
        guildId: Long,
        channelId: Long? = null,
        responseMode: String = "balanced",
        requestedCandidates: Int = 1,
    ): MultiResponseFanoutRecommendation = fanoutPlanner.recommendFanout(guildId, channelId, responseMode, requestedCandidates)

    private fun disabledPolicy(
        guildPolicy: MultiResponsePolicyEntity?,
        channelPolicy: MultiResponsePolicyEntity?,
    ): MultiResponsePolicyEntity? = with(policyResolver) { disabledPolicy(guildPolicy, channelPolicy) }

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

    private fun MultiResponsePolicyEntity.isDisabled(): Boolean = with(policyResolver) { isDisabled() }

    private fun MultiResponsePolicyEntity.disabledMessage(): String = with(policyResolver) { disabledMessage() }

    private fun disabledReasonForMode(mode: String): String? = policyResolver.disabledReasonForMode(mode)

    private fun sanitizeDisabledReason(reason: String?): String? = policyResolver.sanitizeDisabledReason(reason)

    private fun selectProviders(
        guildId: Long,
        policy: MultiResponsePolicyEntity,
        maxCandidates: Int = policy.maxCandidates,
        fanoutAllowed: Boolean = true,
    ): List<ProviderCapabilityProfileEntity> = with(providerSelector) { selectProviders(guildId, policy, maxCandidates, fanoutAllowed) }

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
    ): String = policyResolver.runtimeObservationMode(responseMode, maxCandidates)

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

    private fun CandidateAnswerEntity.hasBlockingSafetyFlag(): Boolean = with(candidateSummaries) { hasBlockingSafetyFlag() }

    private fun failureSummary(runCandidates: List<CandidateAnswerEntity>): String = candidateSummaries.failureSummary(runCandidates)

    private fun synthesisAllowed(
        strategy: String,
        selectedCandidateIds: List<Long>,
    ): Boolean = policyResolver.synthesisAllowed(strategy, selectedCandidateIds)

    private fun ProviderCapabilityProfileEntity.firstModel(): String? = with(providerSelector) { firstModel() }

    private fun summarizeSafety(runCandidates: List<CandidateAnswerEntity>): String = candidateSummaries.summarizeSafety(runCandidates)

    private fun summarizeQuality(runCandidates: List<CandidateAnswerEntity>): String = candidateSummaries.summarizeQuality(runCandidates)

    private fun sanitizeText(
        value: String?,
        maxLength: Int,
    ): String? = promptSafety.sanitizeText(value, maxLength)

    private fun String?.isSensitivePrompt(): Boolean = with(promptSafety) { isSensitivePrompt() }

    private fun sanitizeRequestId(requestId: String): String = promptSafety.sanitizeRequestId(requestId)

    // newRequestId 는 @Transactional 메서드의 기본 인자(requestId = newRequestId())로 평가된다.
    // CGLIB 프록시는 생성자를 거치지 않아 주입 필드(promptSafety 등)가 null 이므로, 기본 인자 평가가
    // 프록시 인스턴스에서 일어나면 NPE 가 난다. 따라서 이 메서드는 주입 필드를 절대 참조하지 않고
    // 자기완결(UUID)로 유지한다(원본과 동일 — 동작 불변).
    private fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "")
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
