package com.discordassistant.central.routing.domain.service

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ModelQualityTier
import com.discordassistant.central.routing.domain.model.RoutingCircuitState
import com.discordassistant.central.routing.domain.model.RoutingDecision
import com.discordassistant.central.routing.domain.model.RoutingLambdas
import com.discordassistant.central.routing.domain.model.RoutingScoreBreakdown
import com.discordassistant.central.routing.domain.model.predictedOutputQuantile
import org.springframework.stereotype.Component
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

data class Selection(
    val providerId: Long,
    val score: Double,
    val reason: String,
    val breakdown: RoutingScoreBreakdown? = null,
)

@Component
class ProviderRouter {
    fun scoreResult(
        c: Candidate,
        ctx: RequestContext,
    ): RoutingScoreBreakdown {
        val lambdas = sanitizeLambdas(c.lambdas)

        val predictedOutputTokens =
            predictedOutputQuantile(
                responseMode = ctx.responseMode,
                p50 = ctx.predictedOutputP50,
                p90 = ctx.predictedOutputP90,
                p95 = ctx.predictedOutputP95,
            ).coerceIn(1, ctx.maxOutputTokens.coerceAtLeast(1))

        val workMillis =
            predictedMarginalWorkMillis(
                c = c,
                ctx = ctx,
                predictedOutputTokens = predictedOutputTokens,
            ).cleanPositive(DEFAULT_WORK_MILLIS)
                .coerceAtLeast(EPSILON_MILLIS)

        val sloAttainmentProbability =
            sloAttainmentProbability(
                c = c,
                ctx = ctx,
                predictedWorkMillis = workMillis,
            )

        val failureProbability = providerFailureProbability(c)
        val sloSuccessProbability =
            ((1.0 - failureProbability) * sloAttainmentProbability)
                .coerceIn(0.0, 1.0)

        val sloLoss =
            sloLoss(
                c = c,
                ctx = ctx,
                predictedWorkMillis = workMillis,
            )

        val usefulGain =
            expectedUsefulGain(
                c = c,
                ctx = ctx,
                sloSuccessProbability = sloSuccessProbability,
            )

        val burden = predictedBurden(c, workMillis)
        val quotaUse = ctx.quotaReservationUnits.coerceAtLeast(0).toDouble()
        val failureRisk = failureProbability * (workMillis / 1_000.0)
        val fairnessBonus = fairnessBonus(lambdas, ctx, predictedOutputTokens)
        val explorationBonus = safeExplorationBonus(c, ctx)

        val numerator =
            usefulGain -
                lambdas.slo * sloLoss -
                lambdas.burden * burden -
                lambdas.quota * quotaUse -
                lambdas.failure * failureRisk +
                fairnessBonus +
                explorationBonus

        val denominator = max(EPSILON_MILLIS, workMillis)
        val index =
            if (numerator.isFinite() && denominator.isFinite() && denominator > 0.0) {
                numerator / denominator
            } else {
                null
            }

        return RoutingScoreBreakdown(
            providerId = c.providerId,
            feasible = index != null,
            infeasibleReasons = if (index == null) listOf("NON_FINITE_SCORE") else emptyList(),
            index = index,
            expectedUsefulGain = usefulGain.cleanNonNegative(),
            expectedSloLoss = sloLoss.cleanNonNegative(),
            expectedBurden = burden.cleanNonNegative(),
            expectedQuotaUse = quotaUse.cleanNonNegative(),
            expectedFailureRisk = failureRisk.cleanNonNegative(),
            expectedWorkMillis = workMillis.cleanPositive(DEFAULT_WORK_MILLIS),
            predictedOutputTokens = predictedOutputTokens,
            predictedSloProbability = sloSuccessProbability.coerceIn(0.0, 1.0),
            fairnessBonus = fairnessBonus.cleanNonNegative(),
            explorationBonus = explorationBonus.cleanNonNegative(),
            lambdas = lambdas,
        )
    }

    fun score(
        c: Candidate,
        ctx: RequestContext,
    ): Double = scoreResult(c, ctx).index ?: Double.NEGATIVE_INFINITY

    fun select(
        candidates: List<Candidate>,
        ctx: RequestContext,
    ): Selection? {
        val best =
            chooseBest(
                candidates.map { candidate ->
                    candidate to scoreResult(candidate, ctx)
                },
            ) ?: return null

        val index = best.second.index ?: return null
        if (index < 0.0) return null

        return Selection(
            providerId = best.first.providerId,
            score = index,
            reason = String.format(Locale.US, "index %.6f", index),
            breakdown = best.second,
        )
    }

    fun decide(
        candidates: List<Candidate>,
        dropped: Map<Long, String>,
        ctx: RequestContext,
        nowMillis: Long = System.currentTimeMillis(),
    ): RoutingDecision {
        val droppedBreakdowns =
            dropped.map { (providerId, reason) ->
                RoutingScoreBreakdown(
                    providerId = providerId,
                    feasible = false,
                    infeasibleReasons = listOf(reason),
                )
            }

        val scored =
            candidates.map { candidate ->
                candidate to scoreResult(candidate, ctx)
            }

        val allBreakdowns = droppedBreakdowns + scored.map { it.second }
        val best = chooseBest(scored)

        return when {
            best != null && (best.second.index ?: Double.NEGATIVE_INFINITY) >= 0.0 ->
                RoutingDecision.ImmediateDispatch(
                    providerId = best.first.providerId,
                    score = best.second.index ?: 0.0,
                    breakdowns = allBreakdowns,
                )

            ctx.retryCount < ctx.maxRetryCount ->
                RoutingDecision.Queue(
                    reason = if (candidates.isEmpty()) "NO_FEASIBLE_PROVIDER_AFTER_FILTER" else "NO_NON_NEGATIVE_SCORE",
                    nextEvaluationAtMillis = nowMillis + REQUEUE_DELAY_MILLIS,
                    breakdowns = allBreakdowns,
                )

            else ->
                RoutingDecision.Reject(
                    reason = "NO_FEASIBLE_PROVIDER",
                    breakdowns = allBreakdowns,
                )
        }
    }

    private fun chooseBest(scored: List<Pair<Candidate, RoutingScoreBreakdown>>): Pair<Candidate, RoutingScoreBreakdown>? {
        val comparator =
            compareByDescending<Pair<Candidate, RoutingScoreBreakdown>> {
                it.second.index ?: Double.NEGATIVE_INFINITY
            }.thenBy {
                it.first.recentHandled
            }.thenBy {
                it.first.activeRequests
            }.thenBy {
                it.first.providerId
            }

        val selectable =
            scored.asSequence().filter { pair ->
                val breakdown = pair.second
                breakdown.isSelectable &&
                    (breakdown.index ?: Double.NEGATIVE_INFINITY).isFinite()
            }

        return selectable.sortedWith(comparator).firstOrNull()
    }

    private fun expectedUsefulGain(
        c: Candidate,
        ctx: RequestContext,
        sloSuccessProbability: Double,
    ): Double {
        val rawGain =
            ctx.priorityValue.coerceAtLeast(0.0) *
                qualityFit(c, ctx) *
                sloSuccessProbability.coerceIn(0.0, 1.0)

        val uncertaintyPenalty =
            UNCERTAINTY_PENALTY_SCALE /
                sqrt(c.observedSampleCount.coerceAtLeast(0).toDouble() + 1.0)

        return (rawGain - uncertaintyPenalty).coerceAtLeast(0.0)
    }

    private fun qualityFit(
        c: Candidate,
        ctx: RequestContext,
    ): Double {
        val tier = ModelQualityTier.fromWire(c.qualityTier)
        if (tier.rank < ctx.requiredQualityTier.rank) return 0.0

        val tierSurplus = (tier.rank - ctx.requiredQualityTier.rank).coerceAtLeast(0)
        val tierBonus = (tierSurplus * 0.04).coerceAtMost(0.12)
        val top = topBurden(c)
        val burdenFit =
            when {
                ctx.requiredBurden !in c.supportedBurdens -> 0.0
                top == ctx.requiredBurden -> 1.0
                top.rank > ctx.requiredBurden.rank -> 0.94
                else -> 0.0
            }

        return (burdenFit + tierBonus).coerceIn(0.0, 1.12)
    }

    private fun providerFailureProbability(c: Candidate): Double {
        val observedFailure =
            1.0 - sanitizeRate(c.observedSuccessRate, DEFAULT_OBSERVED_SUCCESS_RATE)
        val explicitFailure =
            sanitizeRate(c.failureRate, DEFAULT_FAILURE_RATE)
        val timeout =
            sanitizeRate(c.observedTimeoutRate, DEFAULT_TIMEOUT_RATE)
        val coldStartPrior =
            if (c.isColdStart()) COLD_START_FAILURE_PRIOR else 0.0

        return maxOf(
            observedFailure,
            explicitFailure,
            timeout,
            coldStartPrior,
        ).coerceIn(0.0, MAX_FAILURE_PROBABILITY)
    }

    private fun sloAttainmentProbability(
        c: Candidate,
        ctx: RequestContext,
        predictedWorkMillis: Double,
    ): Double {
        val e2eScale = max(1_000.0, ctx.deadlineE2eMillis.toDouble() * 0.22)
        val ttftScale = max(500.0, ctx.deadlineTtftMillis.toDouble() * 0.24)
        val tbtScale = max(100.0, ctx.deadlineTbtMillis.toDouble() * 0.30)

        val e2e =
            sigmoid(
                (ctx.deadlineE2eMillis.toDouble() - predictedWorkMillis) /
                    e2eScale,
            )
        val ttft =
            sigmoid(
                (ctx.deadlineTtftMillis.toDouble() - predictedTtftMillis(c, ctx)) /
                    ttftScale,
            )
        val tbt =
            if (ctx.streamingRequired) {
                sigmoid(
                    (ctx.deadlineTbtMillis.toDouble() - predictedTbtMillis(c)) /
                        tbtScale,
                )
            } else {
                1.0
            }

        return minOf(e2e, ttft, tbt).coerceIn(0.0, 1.0)
    }

    private fun sloLoss(
        c: Candidate,
        ctx: RequestContext,
        predictedWorkMillis: Double,
    ): Double {
        val ttftDeadline = ctx.deadlineTtftMillis.toDouble().cleanPositive(1.0)
        val tbtDeadline = ctx.deadlineTbtMillis.toDouble().cleanPositive(1.0)
        val e2eDeadline = ctx.deadlineE2eMillis.toDouble().cleanPositive(1.0)

        val ttftLoss =
            (predictedTtftMillis(c, ctx) - ttftDeadline).coerceAtLeast(0.0) / ttftDeadline
        val tbtLoss =
            if (ctx.streamingRequired) {
                (predictedTbtMillis(c) - tbtDeadline).coerceAtLeast(0.0) / tbtDeadline
            } else {
                0.0
            }
        val e2eLoss =
            (predictedWorkMillis - e2eDeadline).coerceAtLeast(0.0) / e2eDeadline

        return (ttftLoss + tbtLoss + e2eLoss).coerceIn(0.0, MAX_SLO_LOSS)
    }

    private fun predictedTtftMillis(
        c: Candidate,
        ctx: RequestContext,
    ): Double {
        val prefillMillis =
            effectivePrefillTokens(c, ctx) /
                safeRate(c.prefillTokensPerSecondEma, DEFAULT_PREFILL_TOKENS_PER_SECOND) *
                1_000.0

        return queueWaitMillis(c) +
            c.networkRttEmaMillis.cleanPositive(DEFAULT_NETWORK_RTT_MILLIS) +
            prefillMillis
    }

    private fun predictedTbtMillis(c: Candidate): Double {
        val base =
            1_000.0 /
                safeRate(c.decodeTokensPerSecondEma, DEFAULT_DECODE_TOKENS_PER_SECOND)
        val concurrencyLimit = c.effectiveConcurrencyLimit().coerceAtLeast(1)
        val activePressure =
            c.activeRequests.coerceAtLeast(0).toDouble() /
                concurrencyLimit.toDouble()
        val pressureMultiplier =
            1.0 + activePressure.coerceAtLeast(0.0) * TBT_ACTIVE_PRESSURE_WEIGHT

        return (base * pressureMultiplier).coerceAtLeast(1.0)
    }

    private fun predictedMarginalWorkMillis(
        c: Candidate,
        ctx: RequestContext,
        predictedOutputTokens: Int,
    ): Double {
        val prefillMillis =
            effectivePrefillTokens(c, ctx) /
                safeRate(c.prefillTokensPerSecondEma, DEFAULT_PREFILL_TOKENS_PER_SECOND) *
                1_000.0
        val decodeMillis =
            predictedOutputTokens.toDouble() /
                safeRate(c.decodeTokensPerSecondEma, DEFAULT_DECODE_TOKENS_PER_SECOND) *
                1_000.0
        val phaseServiceMillis = prefillMillis + decodeMillis
        val observedFloorMillis =
            if (c.observedSampleCount >= OBSERVED_LATENCY_MIN_SAMPLES && c.observedLatencyMillis > 0) {
                c.observedLatencyMillis.toDouble() * OBSERVED_LATENCY_FLOOR_RATIO
            } else {
                0.0
            }
        val serviceMillis = max(phaseServiceMillis, observedFloorMillis)

        return queueWaitMillis(c) +
            c.networkRttEmaMillis.cleanPositive(DEFAULT_NETWORK_RTT_MILLIS) +
            serviceMillis
    }

    private fun effectivePrefillTokens(
        c: Candidate,
        ctx: RequestContext,
    ): Double {
        val hit =
            ctx.prefixFingerprint
                ?.let { c.cacheHitTokensByPrefix[it] }
                ?.coerceIn(0, ctx.promptTokens)
                ?: 0

        return (ctx.promptTokens - hit).coerceAtLeast(0).toDouble()
    }

    private fun queueWaitMillis(c: Candidate): Double {
        val concurrencyLimit = c.effectiveConcurrencyLimit().coerceAtLeast(1)
        val pendingPrefillMillis =
            c.estimatedPendingPrefillTokens.coerceAtLeast(0).toDouble() /
                safeRate(c.prefillTokensPerSecondEma, DEFAULT_PREFILL_TOKENS_PER_SECOND) *
                1_000.0
        val pendingDecodeMillis =
            c.estimatedPendingDecodeTokens.coerceAtLeast(0).toDouble() /
                safeRate(c.decodeTokensPerSecondEma, DEFAULT_DECODE_TOKENS_PER_SECOND) *
                1_000.0
        val pendingWorkMillis =
            c.estimatedPendingWorkMillis.cleanNonNegative() +
                pendingPrefillMillis +
                pendingDecodeMillis
        val activePressure =
            c.activeRequests.coerceAtLeast(0).toDouble() /
                concurrencyLimit.toDouble()

        return pendingWorkMillis / concurrencyLimit.toDouble() +
            activePressure.coerceAtLeast(0.0) * ACTIVE_PRESSURE_WAIT_MILLIS
    }

    private fun predictedBurden(
        c: Candidate,
        workMillis: Double,
    ): Double {
        val concurrencyLimit = c.effectiveConcurrencyLimit().coerceAtLeast(1)
        val workPressure =
            workMillis /
                (concurrencyLimit.toDouble() * BURDEN_NORMALIZATION_WINDOW_MILLIS)
        val recentPressure =
            c.recentHandled.coerceAtLeast(0).toDouble() /
                RECENT_HANDLED_NORMALIZATION

        return (workPressure + recentPressure).coerceIn(0.0, MAX_BURDEN_PRESSURE)
    }

    private fun topBurden(c: Candidate): ModelBurden =
        c.supportedBurdens
            .filter { it != ModelBurden.RESTRICTED }
            .maxByOrNull { it.rank }
            ?: ModelBurden.LIGHT

    private fun fairnessBonus(
        lambdas: RoutingLambdas,
        ctx: RequestContext,
        predictedOutputTokens: Int,
    ): Double =
        (lambdas.fairness * serviceCost(ctx, predictedOutputTokens))
            .cleanNonNegative()
            .coerceAtMost(MAX_FAIRNESS_BONUS)

    private fun serviceCost(
        ctx: RequestContext,
        predictedOutputTokens: Int,
    ): Double =
        (ctx.promptTokens * INPUT_TOKEN_SERVICE_WEIGHT + predictedOutputTokens)
            .coerceAtLeast(1.0) /
            SERVICE_COST_NORMALIZATION

    private fun safeExplorationBonus(
        c: Candidate,
        ctx: RequestContext,
    ): Double {
        if (!ctx.isSafeCanaryRequest()) return 0.0
        if (c.inCooldown) return 0.0
        if (!c.state.isOnline) return 0.0
        if (c.circuitState != RoutingCircuitState.CLOSED) return 0.0
        if (c.observedSampleCount >= EXPLORATION_SAMPLE_LIMIT) return 0.0

        return EXPLORATION_BONUS_SCALE /
            sqrt(c.observedSampleCount.coerceAtLeast(0).toDouble() + 1.0)
    }

    private fun predictedOutputQuantile(
        responseMode: String,
        p50: Int,
        p90: Int,
        p95: Int,
    ): Int {
        val safeP50 = p50.coerceAtLeast(1)
        val safeP90 = p90.coerceAtLeast(safeP50)
        val safeP95 = p95.coerceAtLeast(safeP90)

        return when (responseMode.lowercase(Locale.ROOT)) {
            "fast", "short", "cheap", "batch" -> safeP50
            "quality", "careful", "accurate", "creative", "long", "deep" -> safeP95
            else -> safeP90
        }
    }

    private fun sigmoid(value: Double): Double {
        val clamped = value.coerceIn(-60.0, 60.0)
        return 1.0 / (1.0 + exp(-clamped))
    }

    private fun safeRate(
        value: Double,
        default: Double,
    ): Double = value.cleanPositive(default)

    private fun sanitizeRate(
        value: Double,
        default: Double,
    ): Double = if (value.isFinite()) value.coerceIn(0.0, 1.0) else default.coerceIn(0.0, 1.0)

    private fun sanitizeLambdas(raw: RoutingLambdas): RoutingLambdas =
        RoutingLambdas(
            slo = raw.slo.cleanLambda(),
            burden = raw.burden.cleanLambda(),
            quota = raw.quota.cleanLambda(),
            failure = raw.failure.cleanLambda(),
            fairness = raw.fairness.cleanLambda(),
        )

    companion object {
        private const val EPSILON_MILLIS = 1.0
        private const val REQUEUE_DELAY_MILLIS = 250L

        private const val DEFAULT_WORK_MILLIS = 1_000.0
        private const val DEFAULT_PREFILL_TOKENS_PER_SECOND = 600.0
        private const val DEFAULT_DECODE_TOKENS_PER_SECOND = 90.0
        private const val DEFAULT_NETWORK_RTT_MILLIS = 40.0

        private const val DEFAULT_OBSERVED_SUCCESS_RATE = 0.94
        private const val DEFAULT_FAILURE_RATE = 0.06
        private const val DEFAULT_TIMEOUT_RATE = 0.02
        private const val COLD_START_FAILURE_PRIOR = 0.12
        private const val MAX_FAILURE_PROBABILITY = 0.97

        private const val UNCERTAINTY_PENALTY_SCALE = 0.05
        private const val EXPLORATION_SAMPLE_LIMIT = 20
        private const val EXPLORATION_BONUS_SCALE = 0.04

        private const val OBSERVED_LATENCY_MIN_SAMPLES = 8
        private const val OBSERVED_LATENCY_FLOOR_RATIO = 0.50

        private const val TBT_ACTIVE_PRESSURE_WEIGHT = 0.20
        private const val ACTIVE_PRESSURE_WAIT_MILLIS = 250.0

        private const val BURDEN_NORMALIZATION_WINDOW_MILLIS = 10_000.0
        private const val RECENT_HANDLED_NORMALIZATION = 2.5
        private const val MAX_BURDEN_PRESSURE = 10.0

        private const val INPUT_TOKEN_SERVICE_WEIGHT = 0.4
        private const val SERVICE_COST_NORMALIZATION = 1_000.0
        private const val MAX_FAIRNESS_BONUS = 2.0

        private const val MAX_SLO_LOSS = 6.0
    }
}

private fun Double.cleanNonNegative(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0

private fun Double.cleanPositive(default: Double): Double = if (isFinite() && this > 0.0) this else default

private fun Double.cleanLambda(): Double = if (isFinite()) coerceIn(0.0, 8.0) else 0.0
