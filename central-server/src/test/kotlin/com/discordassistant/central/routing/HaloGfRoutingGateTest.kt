package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ModelQualityTier
import com.discordassistant.central.provider.domain.model.ProviderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.max

private fun gateCandidate(
    id: Long = 1,
    state: ProviderState = ProviderState.ONLINE_IDLE,
    supportedBurdens: Set<ModelBurden> = setOf(ModelBurden.LIGHT, ModelBurden.STANDARD, ModelBurden.HEAVY),
    maxConcurrency: Int = 4,
    activeRequests: Int = 0,
    remainingDaily: Int = 1_000,
    allowedRoleIds: Set<Long>? = null,
    allowedChannelIds: Set<Long>? = null,
    maxPromptChars: Int = 100_000,
    failureRate: Double = 0.0,
    inCooldown: Boolean = false,
    recentHandled: Int = 0,
    modelNames: Set<String> = setOf("llama3:8b"),
    qualityTier: String = "high",
    observedSuccessRate: Double = 0.94,
    observedTimeoutRate: Double = 0.0,
    observedLatencyMillis: Long = 0,
    observedOutputChars: Int = 0,
    observedSampleCount: Int = 8,
    contextLimitTokens: Int = 32_000,
    supportsStreaming: Boolean = true,
    supportsTools: Boolean = true,
    supportsJsonMode: Boolean = true,
    modelFamilies: Set<String> = modelNames.map { it.substringBefore(":") }.toSet(),
    blockedProvider: Boolean = false,
    privacyCapabilities: Set<RoutingPrivacyPolicy> = setOf(RoutingPrivacyPolicy.STANDARD, RoutingPrivacyPolicy.LOCAL_ONLY),
    heartbeatAgeMillis: Long = 0L,
    circuitState: RoutingCircuitState = RoutingCircuitState.CLOSED,
    trustedConcurrency: Int = maxConcurrency,
    centralReservedQuotaUnits: Int = 0,
    estimatedPendingPrefillTokens: Int = 0,
    estimatedPendingDecodeTokens: Int = 0,
    estimatedPendingWorkMillis: Double = 0.0,
    prefillTokensPerSecondEma: Double = 600.0,
    decodeTokensPerSecondEma: Double = 90.0,
    networkRttEmaMillis: Double = 40.0,
    cacheHitTokensByPrefix: Map<String, Int> = emptyMap(),
    lambdas: RoutingLambdas = RoutingLambdas(),
) = Candidate(
    providerId = id,
    state = state,
    supportedBurdens = supportedBurdens,
    maxConcurrency = maxConcurrency,
    activeRequests = activeRequests,
    remainingDaily = remainingDaily,
    allowedRoleIds = allowedRoleIds,
    allowedChannelIds = allowedChannelIds,
    maxPromptChars = maxPromptChars,
    failureRate = failureRate,
    inCooldown = inCooldown,
    recentHandled = recentHandled,
    modelNames = modelNames,
    qualityTier = qualityTier,
    observedSuccessRate = observedSuccessRate,
    observedTimeoutRate = observedTimeoutRate,
    observedLatencyMillis = observedLatencyMillis,
    observedOutputChars = observedOutputChars,
    observedSampleCount = observedSampleCount,
    contextLimitTokens = contextLimitTokens,
    supportsStreaming = supportsStreaming,
    supportsTools = supportsTools,
    supportsJsonMode = supportsJsonMode,
    modelFamilies = modelFamilies,
    blockedProvider = blockedProvider,
    privacyCapabilities = privacyCapabilities,
    heartbeatAgeMillis = heartbeatAgeMillis,
    circuitState = circuitState,
    trustedConcurrency = trustedConcurrency,
    centralReservedQuotaUnits = centralReservedQuotaUnits,
    estimatedPendingPrefillTokens = estimatedPendingPrefillTokens,
    estimatedPendingDecodeTokens = estimatedPendingDecodeTokens,
    estimatedPendingWorkMillis = estimatedPendingWorkMillis,
    prefillTokensPerSecondEma = prefillTokensPerSecondEma,
    decodeTokensPerSecondEma = decodeTokensPerSecondEma,
    networkRttEmaMillis = networkRttEmaMillis,
    cacheHitTokensByPrefix = cacheHitTokensByPrefix,
    lambdas = lambdas,
)

private fun gateContext(
    requiredBurden: ModelBurden = ModelBurden.LIGHT,
    promptChars: Int = 200,
    promptTokens: Int = max(1, promptChars / 4),
    maxOutputTokens: Int = 512,
    responseMode: String = "fast",
    requestId: String = "req-1",
    userId: Long = 10L,
    requiredQualityTier: ModelQualityTier = ModelQualityTier.STANDARD,
    allowedModelFamilies: Set<String> = emptySet(),
    blockedProviderIds: Set<Long> = emptySet(),
    streamingRequired: Boolean = false,
    toolsRequired: Boolean = false,
    jsonModeRequired: Boolean = false,
    privacyPolicy: RoutingPrivacyPolicy = RoutingPrivacyPolicy.STANDARD,
    prefixFingerprint: String? = null,
    retryCount: Int = 0,
    maxRetryCount: Int = 1,
    priorityValue: Double = 1.0,
    quotaReservationUnits: Int = 1,
    highPriority: Boolean = false,
    hedgingAllowed: Boolean = false,
    deadlineTtftMillis: Long = defaultDeadlineMillis(requiredBurden, responseMode) / 2,
    deadlineTbtMillis: Long = 1_500L,
    deadlineE2eMillis: Long = defaultDeadlineMillis(requiredBurden, responseMode),
) = RequestContext(
    requiredBurden = requiredBurden,
    requesterRoleIds = setOf(1L),
    channelId = 200L,
    promptChars = promptChars,
    responseMode = responseMode,
    requestId = requestId,
    userId = userId,
    promptTokens = promptTokens,
    maxOutputTokens = maxOutputTokens,
    predictedOutputP50 = max(1, maxOutputTokens / 2),
    predictedOutputP90 = max(1, (maxOutputTokens * 0.9).toInt()),
    predictedOutputP95 = maxOutputTokens,
    deadlineTtftMillis = deadlineTtftMillis,
    deadlineTbtMillis = deadlineTbtMillis,
    deadlineE2eMillis = deadlineE2eMillis,
    requiredQualityTier = requiredQualityTier,
    allowedModelFamilies = allowedModelFamilies,
    blockedProviderIds = blockedProviderIds,
    streamingRequired = streamingRequired,
    toolsRequired = toolsRequired,
    jsonModeRequired = jsonModeRequired,
    privacyPolicy = privacyPolicy,
    prefixFingerprint = prefixFingerprint,
    retryCount = retryCount,
    maxRetryCount = maxRetryCount,
    priorityValue = priorityValue,
    quotaReservationUnits = quotaReservationUnits,
    highPriority = highPriority,
    hedgingAllowed = hedgingAllowed,
)

private fun dualInput(
    attemptId: String,
    providerId: Long = 1,
    userId: Long = 1,
    requestClass: ModelBurden = ModelBurden.LIGHT,
    finalState: AttemptFinalState = AttemptFinalState.SUCCESS,
    sloMet: Boolean = true,
    qualityMet: Boolean = true,
    countsForGoodput: Boolean = finalState == AttemptFinalState.SUCCESS && sloMet && qualityMet,
    quotaPressure: Double = 0.0,
    providerBurdenPressure: Double = 0.0,
    usefulServiceCost: Double = 0.0,
    failureType: RoutingFailureType = RoutingFailureType.NONE,
    userWeight: Double = 1.0,
) = DualUpdateInput(
    attemptId = attemptId,
    providerId = providerId,
    userId = userId,
    requestClass = requestClass,
    finalState = finalState,
    sloMet = sloMet,
    qualityMet = qualityMet,
    countsForGoodput = countsForGoodput,
    quotaPressure = quotaPressure,
    providerBurdenPressure = providerBurdenPressure,
    usefulServiceCost = usefulServiceCost,
    failureType = failureType,
    userWeight = userWeight,
)

class HaloGfHardConstraintGateTest {
    private val filter = ProviderFilterPipeline()
    private val router = ProviderRouter()

    @Test
    fun `Gate 1 - hard constraints are strict filters with auditable reasons`() {
        val cases =
            listOf(
                Triple(
                    gateCandidate(contextLimitTokens = 4_096),
                    gateContext(promptTokens = 3_500, maxOutputTokens = 1_000),
                    "CONTEXT_LIMIT_EXCEEDED",
                ),
                Triple(
                    gateCandidate(qualityTier = "standard"),
                    gateContext(requiredQualityTier = ModelQualityTier.HIGH),
                    "QUALITY_TIER_INSUFFICIENT",
                ),
                Triple(gateCandidate(inCooldown = true), gateContext(), "COOLDOWN"),
                Triple(gateCandidate(circuitState = RoutingCircuitState.OPEN), gateContext(), "CIRCUIT_OPEN"),
                Triple(gateCandidate(heartbeatAgeMillis = 91_000L), gateContext(), "HEARTBEAT_EXPIRED"),
                Triple(
                    gateCandidate(maxConcurrency = 1, activeRequests = 1, trustedConcurrency = 1),
                    gateContext(),
                    "CONCURRENCY_FULL",
                ),
                Triple(gateCandidate(remainingDaily = 0), gateContext(), "QUOTA_INSUFFICIENT"),
                Triple(
                    gateCandidate(privacyCapabilities = setOf(RoutingPrivacyPolicy.STANDARD)),
                    gateContext(privacyPolicy = RoutingPrivacyPolicy.LOCAL_ONLY),
                    "PRIVACY_MISMATCH",
                ),
                Triple(gateCandidate(supportsStreaming = false), gateContext(streamingRequired = true), "STREAMING_UNSUPPORTED"),
                Triple(
                    gateCandidate(modelFamilies = setOf("llama")),
                    gateContext(allowedModelFamilies = setOf("qwen")),
                    "MODEL_FAMILY_NOT_ALLOWED",
                ),
                Triple(gateCandidate(id = 77), gateContext(blockedProviderIds = setOf(77)), "REQUEST_BLOCKED_PROVIDER"),
            )

        cases.forEach { (candidate, request, reason) ->
            val outcome = filter.filter(listOf(candidate), request)
            assertEquals(reason, outcome.dropped[candidate.providerId])
            assertTrue(outcome.eligible.isEmpty())
        }
    }

    @Test
    fun `Gate 1 property - infeasible provider is never selected after filtering`() {
        val unsafe = gateCandidate(id = 1, inCooldown = true, qualityTier = "specialized")
        val safe = gateCandidate(id = 2, qualityTier = "high")
        val request = gateContext()
        val filtered = filter.filter(listOf(unsafe, safe), request)
        val decision = router.decide(filtered.eligible, filtered.dropped, request)

        assertEquals("COOLDOWN", filtered.dropped[1])
        assertTrue(decision is RoutingDecision.ImmediateDispatch)
        assertEquals(2L, (decision as RoutingDecision.ImmediateDispatch).providerId)
    }

    @Test
    fun `Gate 8 and 9 - circuit breaker and cold-start canary rules are hard constraints`() {
        val tightColdStart = gateCandidate(observedSampleCount = 0)
        val tightRequest = gateContext(deadlineE2eMillis = 1_000L)
        val safeColdStart = gateContext()
        val halfOpen = gateCandidate(circuitState = RoutingCircuitState.HALF_OPEN)

        assertEquals("COLD_START_UNSAFE", filter.filter(listOf(tightColdStart), tightRequest).dropped[1])
        assertEquals(1, filter.filter(listOf(tightColdStart), safeColdStart).eligible.size)
        assertEquals("HALF_OPEN_UNSAFE", filter.filter(listOf(halfOpen), gateContext(highPriority = true)).dropped[1])
        assertEquals(1, filter.filter(listOf(halfOpen), safeColdStart).eligible.size)
    }
}

class HaloGfScoreGateTest {
    private val router = ProviderRouter()

    @Test
    fun `Gate 2 - score terms are dynamic lambda monotonic not static weighted sum`() {
        val request = gateContext()
        val slow = gateCandidate(observedLatencyMillis = 40_000, lambdas = RoutingLambdas(slo = 0.0))
        val slowHighSlo = slow.copy(lambdas = RoutingLambdas(slo = 5.0))
        val quotaBase = gateCandidate(lambdas = RoutingLambdas(quota = 0.0))
        val quotaHigh = quotaBase.copy(lambdas = RoutingLambdas(quota = 5.0))
        val burdenBase = gateCandidate(estimatedPendingWorkMillis = 8_000.0, lambdas = RoutingLambdas(burden = 0.0))
        val burdenHigh = burdenBase.copy(lambdas = RoutingLambdas(burden = 5.0))
        val failureBase = gateCandidate(observedSuccessRate = 0.70, lambdas = RoutingLambdas(failure = 0.0))
        val failureHigh = failureBase.copy(lambdas = RoutingLambdas(failure = 5.0))
        val fairnessBase = gateCandidate(lambdas = RoutingLambdas(fairness = 0.0))
        val fairnessHigh = fairnessBase.copy(lambdas = RoutingLambdas(fairness = 5.0))

        assertTrue(router.score(slowHighSlo, request) < router.score(slow, request))
        assertTrue(router.score(quotaHigh, request) < router.score(quotaBase, request))
        assertTrue(router.score(burdenHigh, request) < router.score(burdenBase, request))
        assertTrue(router.score(failureHigh, request) < router.score(failureBase, request))
        assertTrue(router.score(fairnessHigh, request) > router.score(fairnessBase, request))
    }

    @Test
    fun `Gate 2 - score is finite and protected against zero denominators`() {
        val breakdown =
            router.scoreResult(
                gateCandidate(prefillTokensPerSecondEma = 0.0, decodeTokensPerSecondEma = 0.0, networkRttEmaMillis = Double.NaN),
                gateContext(maxOutputTokens = 1),
            )

        assertNotNull(breakdown.index)
        assertTrue(breakdown.index!!.isFinite())
        assertTrue(breakdown.expectedWorkMillis > 0.0)
        assertTrue(breakdown.predictedOutputTokens >= 1)
        assertTrue(breakdown.predictedSloProbability in 0.0..1.0)
        assertTrue(breakdown.expectedSloLoss >= 0.0)
        assertTrue(breakdown.expectedFailureRisk >= 0.0)
    }

    @Test
    fun `Gate 3 - provider load uses predicted remaining token work not active count only`() {
        val request =
            gateContext(
                deadlineTtftMillis = 300_000L,
                deadlineTbtMillis = 10_000L,
                deadlineE2eMillis = 300_000L,
            )
        val neutral = RoutingLambdas(slo = 0.0, burden = 0.0, quota = 0.0, failure = 0.0)
        val oneHuge =
            gateCandidate(
                id = 1,
                activeRequests = 1,
                maxConcurrency = 8,
                trustedConcurrency = 8,
                estimatedPendingPrefillTokens = 64_000,
                estimatedPendingDecodeTokens = 8_000,
                lambdas = neutral,
            )
        val threeSmall =
            gateCandidate(
                id = 2,
                activeRequests = 3,
                maxConcurrency = 8,
                trustedConcurrency = 8,
                estimatedPendingPrefillTokens = 3_000,
                estimatedPendingDecodeTokens = 300,
                lambdas = neutral,
            )

        val hugeScore = router.scoreResult(oneHuge, request)
        val smallScore = router.scoreResult(threeSmall, request)

        assertTrue(smallScore.expectedWorkMillis < hugeScore.expectedWorkMillis)
        assertTrue((smallScore.index ?: Double.NEGATIVE_INFINITY) > (hugeScore.index ?: Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `Gate 14 - cache locality is provider-local only`() {
        val request = gateContext(promptTokens = 6_000, prefixFingerprint = "prefix-a")
        val cached = gateCandidate(id = 1, cacheHitTokensByPrefix = mapOf("prefix-a" to 5_000))
        val uncached = gateCandidate(id = 2)

        val cachedScore = router.scoreResult(cached, request)
        val uncachedScore = router.scoreResult(uncached, request)

        assertTrue(cachedScore.expectedWorkMillis < uncachedScore.expectedWorkMillis)
    }
}

class HaloGfLifecycleAndStateGateTest {
    @Test
    fun `Gate 4 and 11 - goodput is separate from success and hedging loser accounting`() {
        val stats = ProviderRoutingStats()
        val latency = RoutingLatencyMetrics.fromMillis(1_000, 1_200, 1_800, 3_000, 7)
        val outcomes =
            listOf(
                RoutingAttemptOutcome(
                    AttemptFinalState.SUCCESS,
                    latency = latency,
                    actualInputTokens = 100,
                    actualOutputTokens = 20,
                    qualityMet = true,
                    sloMet = true,
                ),
                RoutingAttemptOutcome(
                    AttemptFinalState.SUCCESS,
                    latency = latency,
                    actualInputTokens = 100,
                    actualOutputTokens = 20,
                    qualityMet = true,
                    sloMet = false,
                ),
                RoutingAttemptOutcome(
                    AttemptFinalState.SUCCESS,
                    latency = latency,
                    actualInputTokens = 100,
                    actualOutputTokens = 20,
                    qualityMet = false,
                    sloMet = true,
                ),
                RoutingAttemptOutcome(
                    AttemptFinalState.TIMEOUT,
                    failureType = RoutingFailureType.END_TO_END_TIMEOUT,
                    latency = latency,
                    actualInputTokens = 100,
                    actualOutputTokens = 0,
                    qualityMet = false,
                    sloMet = false,
                ),
                RoutingAttemptOutcome(
                    AttemptFinalState.SUCCESS,
                    latency = latency,
                    actualInputTokens = 100,
                    actualOutputTokens = 20,
                    qualityMet = true,
                    sloMet = true,
                    hedgingWinner = false,
                ),
            )

        outcomes.forEach { stats.recordAttempt(1, ModelBurden.LIGHT, it) }
        val snapshot = stats.snapshot(1, ModelBurden.LIGHT)

        assertEquals(5, snapshot.sampleCount)
        assertEquals(4, snapshot.successCount)
        assertEquals(4, snapshot.rawThroughputCount)
        assertEquals(1, snapshot.goodputCount)
        assertTrue(snapshot.wasteTokens > 0)
    }

    @Test
    fun `Gate 11 - hedging requires safe conditions and bounded budget`() {
        val policy = RoutingHedgingPolicy(maxOutstandingHedges = 1)
        val eligible =
            gateContext(
                highPriority = true,
                hedgingAllowed = true,
            )

        assertTrue(policy.tryAcquire(eligible, spareProviderCapacity = 2, deadlineSlackMillis = 1_000L))
        assertEquals(1, policy.outstandingHedges())
        assertTrue(!policy.tryAcquire(eligible, spareProviderCapacity = 2, deadlineSlackMillis = 1_000L))
        policy.release()
        assertEquals(0, policy.outstandingHedges())

        assertTrue(
            !policy.tryAcquire(
                gateContext(highPriority = false, hedgingAllowed = true),
                spareProviderCapacity = 2,
                deadlineSlackMillis = 1_000L,
            ),
        )
        assertTrue(
            !policy.tryAcquire(
                gateContext(highPriority = true, hedgingAllowed = false),
                spareProviderCapacity = 2,
                deadlineSlackMillis = 1_000L,
            ),
        )
        assertTrue(
            !policy.tryAcquire(
                gateContext(highPriority = true, hedgingAllowed = true, retryCount = 1),
                spareProviderCapacity = 2,
                deadlineSlackMillis = 1_000L,
            ),
        )
        assertTrue(!policy.tryAcquire(eligible, spareProviderCapacity = 1, deadlineSlackMillis = 1_000L))
        assertTrue(!policy.tryAcquire(eligible, spareProviderCapacity = 2, deadlineSlackMillis = 3_000L))
    }

    @Test
    fun `Gate 5 - TTFT TBT E2E are measured from arrival`() {
        val latency =
            RoutingLatencyMetrics.fromMillis(
                arrivalAt = 1_000,
                dispatchAt = 1_200,
                firstTokenAt = 1_800,
                completedAt = 3_000,
                generatedTokens = 7,
            )

        assertEquals(200, latency.queueWaitMillis)
        assertEquals(800, latency.ttftMillis)
        assertEquals(2_000, latency.e2eMillis)
        assertEquals(200, latency.averageTbtMillis)
        assertEquals(0, RoutingLatencyMetrics.fromMillis(0, 0, 0, 0, 1).averageTbtMillis)
    }

    @Test
    fun `Gate 6 - concurrent reservation cannot exceed maxConcurrency`() {
        val manager = RoutingReservationManager()
        val candidate = gateCandidate(maxConcurrency = 1, trustedConcurrency = 1)
        val request = gateContext()
        val executor = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val tasks =
            (1..100).map {
                Callable {
                    start.await()
                    manager.tryReserve(candidate, request)
                }
            }

        val futures = tasks.map { executor.submit(it) }
        start.countDown()
        val reserved = futures.count { it.get() is ReservationResult.Reserved }
        executor.shutdown()

        assertEquals(1, reserved)
        assertEquals(1, manager.snapshot(candidate.providerId).maxObservedActive)
    }

    @Test
    fun `Gate 6 - quota reservation cannot go negative under concurrency`() {
        val manager = RoutingReservationManager()
        val candidate = gateCandidate(maxConcurrency = 10, trustedConcurrency = 10, remainingDaily = 1_000)
        val request = gateContext(quotaReservationUnits = 300)
        val executor = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)
        val tasks =
            (1..10).map {
                Callable {
                    start.await()
                    manager.tryReserve(candidate, request)
                }
            }

        val futures = tasks.map { executor.submit(it) }
        start.countDown()
        val reserved = futures.count { it.get() is ReservationResult.Reserved }
        executor.shutdown()
        val snapshot = manager.snapshot(candidate.providerId)

        assertEquals(3, reserved)
        assertEquals(900, snapshot.reservedQuotaUnits)
    }

    @Test
    fun `Gate 7 - attempt finalization is exactly once`() {
        val filter = ProviderFilterPipeline()
        val reservationManager = RoutingReservationManager(filter)
        val stats = ProviderRoutingStats()
        val duals = RoutingDualVariableManager()
        val audit = RoutingAuditLogger()
        val lifecycle = RoutingAttemptLifecycleManager(reservationManager, stats, duals, audit)
        val request = gateContext(requestId = "exactly-once")
        val reservation = (reservationManager.tryReserve(gateCandidate(), request) as ReservationResult.Reserved).reservation
        val attempt = lifecycle.startAttempt(request, reservation)
        val latency = RoutingLatencyMetrics.fromMillis(1_000, 1_200, 1_800, 3_000, 7)
        val outcomes =
            listOf(
                RoutingAttemptOutcome(
                    AttemptFinalState.SUCCESS,
                    latency = latency,
                    actualInputTokens = 100,
                    actualOutputTokens = 20,
                    qualityMet = true,
                    sloMet = true,
                ),
                RoutingAttemptOutcome(
                    AttemptFinalState.TIMEOUT,
                    failureType = RoutingFailureType.END_TO_END_TIMEOUT,
                    latency = latency,
                    actualInputTokens = 100,
                    actualOutputTokens = 0,
                    qualityMet = false,
                    sloMet = false,
                ),
                RoutingAttemptOutcome(
                    AttemptFinalState.CANCELLED,
                    failureType = RoutingFailureType.CENTRAL_CANCELLED,
                    latency = latency,
                    actualInputTokens = 100,
                    actualOutputTokens = 0,
                    qualityMet = false,
                    sloMet = false,
                ),
            )
        val executor = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val futures =
            outcomes.map { outcome ->
                executor.submit(
                    Callable {
                        start.await()
                        lifecycle.finalizeAttempt(attempt, outcome, quotaPressure = 0.0, providerBurdenPressure = 0.0)
                    },
                )
            }

        start.countDown()
        val results = futures.map { it.get() }
        executor.shutdown()

        assertEquals(1, results.count { !it.duplicate })
        assertEquals(2, results.count { it.duplicate })
        assertEquals(0, reservationManager.snapshot(1).activeReservations)
        assertEquals(1, stats.snapshot(1, ModelBurden.LIGHT).sampleCount)
        assertTrue(audit.read("exactly-once")!!.events.count { it.startsWith("duplicate_finalization") } >= 1)
    }

    @Test
    fun `Gate 12 and 13 - dual variables use windows EMA and fairness uses service debt`() {
        val duals = RoutingDualVariableManager()
        val before = duals.snapshot(providerId = 1, userId = 1, requestClass = ModelBurden.LIGHT)
        repeat(20) { index ->
            duals.recordOutcome(
                dualInput(
                    attemptId = "pressure-$index",
                    finalState = AttemptFinalState.TIMEOUT,
                    sloMet = false,
                    qualityMet = false,
                    countsForGoodput = false,
                    quotaPressure = 0.95,
                    providerBurdenPressure = 0.95,
                    failureType = RoutingFailureType.END_TO_END_TIMEOUT,
                ),
            )
        }
        val after = duals.snapshot(providerId = 1, userId = 1, requestClass = ModelBurden.LIGHT)

        assertTrue(after.slo > before.slo)
        assertTrue(after.quota > before.quota)
        assertTrue(after.burden > before.burden)
        assertTrue(after.failure > before.failure)

        val neutral = RoutingDualVariableManager()
        val neutralBefore = neutral.snapshot(providerId = 1, userId = 1, requestClass = ModelBurden.LIGHT)
        repeat(19) { index ->
            neutral.recordOutcome(dualInput(attemptId = "neutral-success-$index"))
        }
        neutral.recordOutcome(
            dualInput(
                attemptId = "neutral-failure",
                finalState = AttemptFinalState.TIMEOUT,
                sloMet = false,
                qualityMet = false,
                countsForGoodput = false,
                failureType = RoutingFailureType.END_TO_END_TIMEOUT,
            ),
        )
        val neutralAfter = neutral.snapshot(providerId = 1, userId = 1, requestClass = ModelBurden.LIGHT)
        assertEquals(neutralBefore.slo, neutralAfter.slo)

        duals.recordOutcome(
            dualInput(
                attemptId = "served-user",
                userId = 1,
                usefulServiceCost = 10_000.0,
            ),
        )
        duals.recordOutcome(
            dualInput(
                attemptId = "under-served-user",
                userId = 2,
                finalState = AttemptFinalState.CANCELLED,
                sloMet = false,
                qualityMet = false,
                countsForGoodput = false,
                failureType = RoutingFailureType.CENTRAL_CANCELLED,
            ),
        )
        val served = duals.snapshot(providerId = 1, userId = 1, requestClass = ModelBurden.LIGHT)
        val underServed = duals.snapshot(providerId = 1, userId = 2, requestClass = ModelBurden.LIGHT)

        assertTrue(underServed.fairness > served.fairness)

        val invalidBefore = duals.snapshot(providerId = 9, userId = 9, requestClass = ModelBurden.LIGHT)
        assertTrue(
            duals.recordOutcome(
                dualInput(
                    attemptId = "invalid-pressure",
                    providerId = 9,
                    userId = 9,
                    quotaPressure = Double.NaN,
                    providerBurdenPressure = Double.POSITIVE_INFINITY,
                ),
            ),
        )
        val invalidAfter = duals.snapshot(providerId = 9, userId = 9, requestClass = ModelBurden.LIGHT)
        assertEquals(invalidBefore.quota, invalidAfter.quota)
        assertEquals(invalidBefore.burden, invalidAfter.burden)
        assertEquals(2, duals.invalidInputs())

        assertTrue(duals.recordOutcome(dualInput(attemptId = "duplicate-outcome")))
        assertTrue(!duals.recordOutcome(dualInput(attemptId = "duplicate-outcome")))
        listOf(after, served, underServed).forEach { lambda ->
            assertTrue(lambda.slo.isFinite() && lambda.slo >= 0.0)
            assertTrue(lambda.quota.isFinite() && lambda.quota >= 0.0)
            assertTrue(lambda.burden.isFinite() && lambda.burden >= 0.0)
            assertTrue(lambda.failure.isFinite() && lambda.failure >= 0.0)
            assertTrue(lambda.fairness.isFinite() && lambda.fairness >= 0.0)
        }
    }

    @Test
    fun `Gate 15 - central observed state overrides provider self-report hints`() {
        val filter = ProviderFilterPipeline()
        val request = gateContext()
        val activeFull = gateCandidate(activeRequests = 3, maxConcurrency = 3, trustedConcurrency = 3)
        val quotaFull = gateCandidate(remainingDaily = 100, centralReservedQuotaUnits = 100)

        assertEquals("CONCURRENCY_FULL", filter.filter(listOf(activeFull), request).dropped[1])
        assertEquals("QUOTA_INSUFFICIENT", filter.filter(listOf(quotaFull), request).dropped[1])
    }

    @Test
    fun `Gate 16 - audit log can reconstruct routing decision`() {
        val audit = RoutingAuditLogger()
        val request = gateContext(requestId = "audit-1")
        val router = ProviderRouter()
        val safe = gateCandidate(id = 2)
        val dropped = mapOf(1L to "COOLDOWN")
        val breakdowns =
            listOf(
                RoutingScoreBreakdown(providerId = 1, feasible = false, infeasibleReasons = listOf("COOLDOWN")),
                router.scoreResult(safe, request),
            )
        val reservation =
            RoutingReservation(
                reservationId = "res-1",
                requestId = request.requestId,
                providerId = 2,
                reservedInputTokens = request.promptTokens,
                reservedOutputTokens = request.maxOutputTokens,
                reservedQuotaUnits = 1,
                estimatedWorkMillis = 1.0,
                createdAtMillis = 123L,
            )

        audit.recordDecision(
            request.requestId,
            selectedProviderId = 2,
            candidateProviderIds = listOf(1, 2),
            infeasibleProviderReasons = dropped,
            scoreBreakdowns = breakdowns,
        )
        audit.recordReservation(request.requestId, reservation)
        val record = audit.read(request.requestId)!!

        assertEquals(2L, record.selectedProviderId)
        assertEquals(listOf(1L, 2L), record.candidateProviderIds)
        assertEquals("COOLDOWN", record.infeasibleProviderReasons[1])
        assertTrue(record.scoreBreakdowns.any { it.providerId == 2L && it.index != null })
        assertEquals("res-1", record.quotaReservationId)
        assertTrue(record.decisionTimestampMillis > 0)
    }
}
