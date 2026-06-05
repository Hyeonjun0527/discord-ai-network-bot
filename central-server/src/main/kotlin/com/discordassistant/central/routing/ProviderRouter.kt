package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ModelQualityTier
import org.springframework.stereotype.Component
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

data class Selection(
    val providerId: Long,
    val score: Double,
    val reason: String,
    val breakdown: RoutingScoreBreakdown? = null,
)

/**
 * 제약 필터를 통과한 후보에 대해 SLO-goodput density 를 계산한다.
 * 계수는 운영 통계가 부족한 초기 상태에서도 안정적으로 동작하도록 보수적 prior 로 둔다.
 */
@Component
class ProviderRouter {
    fun scoreResult(
        c: Candidate,
        ctx: RequestContext,
    ): RoutingScoreBreakdown {
        val lambdas = c.lambdas.finiteClamped()
        val predictedOutputTokens =
            predictedOutputQuantile(
                ctx.responseMode,
                ctx.predictedOutputP50,
                ctx.predictedOutputP90,
                ctx.predictedOutputP95,
            )
        val workMillis = predictedMarginalWorkMillis(c, ctx, predictedOutputTokens)
        val sloProbability = sloProbability(c, ctx, workMillis)
        val sloLoss = sloLoss(c, ctx, workMillis)
        val usefulGain = expectedUsefulGain(c, ctx, sloProbability)
        val burden = predictedBurden(c, workMillis)
        val quotaUse = ctx.quotaReservationUnits.toDouble()
        val failureRisk = effectiveFailureRate(c) * (workMillis / 1_000.0)
        val fairnessBonus = lambdas.fairness * serviceCost(ctx, predictedOutputTokens)
        val explorationBonus = safeExplorationBonus(c, ctx)
        val numerator =
            usefulGain -
                lambdas.slo * sloLoss -
                lambdas.burden * burden -
                lambdas.quota * quotaUse -
                lambdas.failure * failureRisk +
                fairnessBonus +
                explorationBonus
        val denominator = EPSILON_MILLIS + workMillis
        val index = numerator / denominator
        val finiteIndex = index.takeIf { it.isFinite() }
        return RoutingScoreBreakdown(
            providerId = c.providerId,
            feasible = finiteIndex != null,
            infeasibleReasons = if (finiteIndex == null) listOf("NON_FINITE_SCORE") else emptyList(),
            index = finiteIndex,
            expectedUsefulGain = usefulGain.cleanNonNegative(),
            expectedSloLoss = sloLoss.cleanNonNegative(),
            expectedBurden = burden.cleanNonNegative(),
            expectedQuotaUse = quotaUse.cleanNonNegative(),
            expectedFailureRisk = failureRisk.cleanNonNegative(),
            expectedWorkMillis = workMillis.cleanNonNegative().coerceAtLeast(EPSILON_MILLIS),
            predictedOutputTokens = predictedOutputTokens,
            predictedSloProbability = sloProbability.coerceIn(0.0, 1.0),
            fairnessBonus = fairnessBonus.cleanNonNegative(),
            explorationBonus = explorationBonus.cleanNonNegative(),
            lambdas = lambdas,
        )
    }

    fun score(
        c: Candidate,
        ctx: RequestContext,
    ): Double = scoreResult(c, ctx).index ?: Double.NEGATIVE_INFINITY

    private fun expectedUsefulGain(
        c: Candidate,
        ctx: RequestContext,
        sloProbability: Double,
    ): Double {
        val uncertaintyPenalty = 0.05 / sqrt(c.observedSampleCount.toDouble() + 1.0)
        return (ctx.priorityValue * qualityFit(c, ctx) * effectiveSuccessRate(c) * sloProbability - uncertaintyPenalty).coerceAtLeast(0.0)
    }

    private fun qualityFit(
        c: Candidate,
        ctx: RequestContext,
    ): Double {
        val tier = ModelQualityTier.fromWire(c.qualityTier)
        val top = topBurden(c)
        val tierBonus = tier.routingBonus / 10.0
        val burdenFit =
            when {
                top == ctx.requiredBurden -> 1.0
                top.rank == ctx.requiredBurden.rank + 1 -> 0.86
                top.rank > ctx.requiredBurden.rank -> 0.72
                else -> 0.0
            }
        return (burdenFit + tierBonus).coerceIn(0.0, 1.12)
    }

    private fun effectiveSuccessRate(c: Candidate): Double = (c.observedSuccessRate * (1.0 - c.failureRate)).coerceIn(0.03, 0.995)

    private fun effectiveFailureRate(c: Candidate): Double = (1.0 - effectiveSuccessRate(c)).coerceIn(0.0, 0.97)

    private fun sloProbability(
        c: Candidate,
        ctx: RequestContext,
        predictedWorkMillis: Double,
    ): Double {
        val e2eScale = max(1_000.0, ctx.deadlineE2eMillis * 0.22)
        val ttftScale = max(500.0, ctx.deadlineTtftMillis * 0.24)
        val tbtScale = max(100.0, ctx.deadlineTbtMillis * 0.30)
        val e2e = sigmoid((ctx.deadlineE2eMillis - predictedWorkMillis) / e2eScale)
        val ttft = sigmoid((ctx.deadlineTtftMillis - predictedTtftMillis(c, ctx)) / ttftScale)
        val tbt = sigmoid((ctx.deadlineTbtMillis - predictedTbtMillis(c)) / tbtScale)
        return (1.0 - effectiveFailureRate(c)) * minOf(e2e, ttft, tbt).coerceIn(0.0, 1.0)
    }

    private fun sloLoss(
        c: Candidate,
        ctx: RequestContext,
        predictedWorkMillis: Double,
    ): Double {
        val ttftLoss = ((predictedTtftMillis(c, ctx) - ctx.deadlineTtftMillis).coerceAtLeast(0.0) / ctx.deadlineTtftMillis)
        val tbtLoss = ((predictedTbtMillis(c) - ctx.deadlineTbtMillis).coerceAtLeast(0.0) / ctx.deadlineTbtMillis)
        val e2eLoss = ((predictedWorkMillis - ctx.deadlineE2eMillis).coerceAtLeast(0.0) / ctx.deadlineE2eMillis)
        return (ttftLoss + tbtLoss + e2eLoss).coerceIn(0.0, 6.0)
    }

    private fun predictedTtftMillis(
        c: Candidate,
        ctx: RequestContext,
    ): Double {
        val prefill = effectivePrefillTokens(c, ctx) / safeRate(c.prefillTokensPerSecondEma) * 1_000.0
        return c.networkRttEmaMillis.cleanPositive(40.0) + prefill + queueWaitMillis(c) * 0.50
    }

    private fun predictedTbtMillis(c: Candidate): Double = 1_000.0 / safeRate(c.decodeTokensPerSecondEma)

    private fun predictedMarginalWorkMillis(
        c: Candidate,
        ctx: RequestContext,
        predictedOutputTokens: Int,
    ): Double {
        val prefillMillis = effectivePrefillTokens(c, ctx) / safeRate(c.prefillTokensPerSecondEma) * 1_000.0
        val decodeMillis = predictedOutputTokens / safeRate(c.decodeTokensPerSecondEma) * 1_000.0
        val directWork = prefillMillis + decodeMillis + c.networkRttEmaMillis.cleanPositive(40.0)
        val baseWork = c.observedLatencyMillis.takeIf { it > 0 }?.toDouble() ?: directWork
        return (
            baseWork +
                queueWaitMillis(c) +
                c.networkRttEmaMillis.cleanPositive(40.0)
        ).coerceAtLeast(EPSILON_MILLIS)
    }

    private fun effectivePrefillTokens(
        c: Candidate,
        ctx: RequestContext,
    ): Double {
        val hit = ctx.prefixFingerprint?.let { c.cacheHitTokensByPrefix[it] } ?: 0
        return (ctx.promptTokens - hit).coerceAtLeast(0).toDouble()
    }

    private fun queueWaitMillis(c: Candidate): Double {
        val pendingPrefillMillis = c.estimatedPendingPrefillTokens / safeRate(c.prefillTokensPerSecondEma) * 1_000.0
        val pendingDecodeMillis = c.estimatedPendingDecodeTokens / safeRate(c.decodeTokensPerSecondEma) * 1_000.0
        val activePressure = c.activeRequests.toDouble() / c.effectiveConcurrencyLimit().coerceAtLeast(1).toDouble()
        return (
            c.estimatedPendingWorkMillis.cleanNonNegative() +
                pendingPrefillMillis +
                pendingDecodeMillis +
                activePressure.coerceAtLeast(0.0) * 1_500.0
        ).coerceAtLeast(0.0)
    }

    private fun predictedBurden(
        c: Candidate,
        workMillis: Double,
    ): Double =
        workMillis / (c.effectiveConcurrencyLimit().coerceAtLeast(1) * 10_000.0) +
            c.recentHandled.coerceAtLeast(0) * 0.80

    private fun topBurden(c: Candidate): ModelBurden = c.supportedBurdens.maxByOrNull { it.rank } ?: ModelBurden.LIGHT

    private fun sigmoid(value: Double): Double = 1.0 / (1.0 + exp(-value))

    private fun safeRate(value: Double): Double = value.cleanPositive(1.0)

    private fun serviceCost(
        ctx: RequestContext,
        predictedOutputTokens: Int,
    ): Double = (ctx.promptTokens * 0.4 + predictedOutputTokens).coerceAtLeast(1.0) / 1_000.0

    private fun safeExplorationBonus(
        c: Candidate,
        ctx: RequestContext,
    ): Double =
        if (c.observedSampleCount < EXPLORATION_SAMPLE_LIMIT && ctx.isSafeCanaryRequest() && c.circuitState == RoutingCircuitState.CLOSED) {
            0.04 / sqrt(c.observedSampleCount.toDouble() + 1.0)
        } else {
            0.0
        }

    /** 후보 중 최종 1인 선택. 비면 null. 동점은 (점수↓, 최근처리량↑ 적은 순, providerId↑)로 결정. */
    fun select(
        candidates: List<Candidate>,
        ctx: RequestContext,
    ): Selection? {
        if (candidates.isEmpty()) return null
        val best =
            candidates
                .map { it to scoreResult(it, ctx) }
                .filter { it.second.isSelectable }
                .sortedWith(
                    compareByDescending<Pair<Candidate, RoutingScoreBreakdown>> { it.second.index ?: Double.NEGATIVE_INFINITY }
                        .thenBy { it.first.recentHandled }
                        .thenBy { it.first.providerId },
                ).firstOrNull() ?: return null
        val index = best.second.index ?: return null
        if (index < 0.0) return null
        return Selection(best.first.providerId, index, "index ${"%.4f".format(index)}", best.second)
    }

    fun decide(
        candidates: List<Candidate>,
        dropped: Map<Long, String>,
        ctx: RequestContext,
        nowMillis: Long = System.currentTimeMillis(),
    ): RoutingDecision {
        val droppedBreakdowns =
            dropped.map {
                RoutingScoreBreakdown(
                    providerId = it.key,
                    feasible = false,
                    infeasibleReasons = listOf(it.value),
                )
            }
        val scoreBreakdowns = candidates.map { scoreResult(it, ctx) }
        val allBreakdowns = droppedBreakdowns + scoreBreakdowns
        val best =
            scoreBreakdowns
                .filter { it.isSelectable && (it.index ?: Double.NEGATIVE_INFINITY) >= 0.0 }
                .maxByOrNull { it.index ?: Double.NEGATIVE_INFINITY }
        return if (best != null) {
            RoutingDecision.ImmediateDispatch(best.providerId, best.index ?: 0.0, allBreakdowns)
        } else if (ctx.retryCount < ctx.maxRetryCount) {
            RoutingDecision.Queue("NO_NON_NEGATIVE_SCORE", nowMillis + 250L, allBreakdowns)
        } else {
            RoutingDecision.Reject("NO_FEASIBLE_PROVIDER", allBreakdowns)
        }
    }

    companion object {
        private const val EPSILON_MILLIS = 1.0
        private const val EXPLORATION_SAMPLE_LIMIT = 20
    }
}

private fun Double.cleanNonNegative(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0

private fun Double.cleanPositive(default: Double): Double = if (isFinite() && this > 0.0) this else default
