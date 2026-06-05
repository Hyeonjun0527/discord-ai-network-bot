package com.discordassistant.central.routing.domain.model

import com.discordassistant.central.domain.ModelBurden
import java.util.concurrent.TimeUnit
import kotlin.math.max

enum class RoutingPrivacyPolicy {
    STANDARD,
    LOCAL_ONLY,
}

enum class RoutingCircuitState {
    CLOSED,
    HALF_OPEN,
    OPEN,
}

enum class AttemptFinalState {
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED,
    REJECTED_BY_PROVIDER,
    LOST_HEDGE,
}

enum class RoutingFailureType {
    NONE,
    PROVIDER_OFFLINE,
    CONNECTION_TIMEOUT,
    FIRST_TOKEN_TIMEOUT,
    MID_STREAM_TIMEOUT,
    END_TO_END_TIMEOUT,
    QUOTA_EXCEEDED,
    MODEL_ERROR,
    INVALID_RESPONSE,
    CENTRAL_CANCELLED,
    USER_CANCELLED,
}

data class RoutingLambdas(
    val slo: Double = 0.20,
    val burden: Double = 0.06,
    val quota: Double = 0.02,
    val failure: Double = 0.05,
    val fairness: Double = 0.0,
) {
    fun finiteClamped(max: Double = 8.0): RoutingLambdas =
        RoutingLambdas(
            slo = slo.clean(max),
            burden = burden.clean(max),
            quota = quota.clean(max),
            failure = failure.clean(max),
            fairness = fairness.clean(max),
        )

    private fun Double.clean(max: Double): Double = if (isFinite()) coerceIn(0.0, max) else 0.0
}

data class RoutingScoreBreakdown(
    val providerId: Long,
    val feasible: Boolean,
    val infeasibleReasons: List<String> = emptyList(),
    val index: Double? = null,
    val expectedUsefulGain: Double = 0.0,
    val expectedSloLoss: Double = 0.0,
    val expectedBurden: Double = 0.0,
    val expectedQuotaUse: Double = 0.0,
    val expectedFailureRisk: Double = 0.0,
    val expectedWorkMillis: Double = 0.0,
    val predictedOutputTokens: Int = 1,
    val predictedSloProbability: Double = 0.0,
    val fairnessBonus: Double = 0.0,
    val explorationBonus: Double = 0.0,
    val lambdas: RoutingLambdas = RoutingLambdas(),
) {
    val isSelectable: Boolean get() = feasible && index != null && index.isFinite()
}

sealed class RoutingDecision {
    data class ImmediateDispatch(
        val providerId: Long,
        val score: Double,
        val breakdowns: List<RoutingScoreBreakdown>,
    ) : RoutingDecision()

    data class Queue(
        val reason: String,
        val nextEvaluationAtMillis: Long,
        val breakdowns: List<RoutingScoreBreakdown> = emptyList(),
    ) : RoutingDecision()

    data class Fallback(
        val reason: String,
        val fallbackType: String,
        val excludedProviderIds: Set<Long>,
        val breakdowns: List<RoutingScoreBreakdown> = emptyList(),
    ) : RoutingDecision()

    data class Reject(
        val reason: String,
        val breakdowns: List<RoutingScoreBreakdown> = emptyList(),
    ) : RoutingDecision()
}

data class RoutingLatencyMetrics(
    val arrivalAtNanos: Long,
    val dispatchAtNanos: Long,
    val firstTokenAtNanos: Long,
    val completedAtNanos: Long,
    val generatedTokens: Int,
) {
    val queueWaitMillis: Long get() = nanosToMillis(dispatchAtNanos - arrivalAtNanos)
    val ttftMillis: Long get() = nanosToMillis(firstTokenAtNanos - arrivalAtNanos)
    val e2eMillis: Long get() = nanosToMillis(completedAtNanos - arrivalAtNanos)
    val averageTbtMillis: Long
        get() = nanosToMillis((completedAtNanos - firstTokenAtNanos) / max(1, generatedTokens - 1))

    companion object {
        fun fromMillis(
            arrivalAt: Long,
            dispatchAt: Long,
            firstTokenAt: Long,
            completedAt: Long,
            generatedTokens: Int,
        ): RoutingLatencyMetrics =
            RoutingLatencyMetrics(
                arrivalAtNanos = TimeUnit.MILLISECONDS.toNanos(arrivalAt),
                dispatchAtNanos = TimeUnit.MILLISECONDS.toNanos(dispatchAt),
                firstTokenAtNanos = TimeUnit.MILLISECONDS.toNanos(firstTokenAt),
                completedAtNanos = TimeUnit.MILLISECONDS.toNanos(completedAt),
                generatedTokens = generatedTokens,
            )

        private fun nanosToMillis(value: Long): Long = TimeUnit.NANOSECONDS.toMillis(value.coerceAtLeast(0))
    }
}

data class RoutingAttemptOutcome(
    val finalState: AttemptFinalState,
    val failureType: RoutingFailureType = RoutingFailureType.NONE,
    val latency: RoutingLatencyMetrics,
    val actualInputTokens: Int,
    val actualOutputTokens: Int,
    val qualityMet: Boolean,
    val sloMet: Boolean,
    val hedgingWinner: Boolean = true,
) {
    val success: Boolean get() = finalState == AttemptFinalState.SUCCESS
    val contributesGoodput: Boolean get() = success && sloMet && qualityMet && hedgingWinner
}

fun estimatedMaxOutputTokens(responseMode: String): Int =
    when (responseMode.lowercase()) {
        "fast" -> 512
        "deep" -> 2_048
        "saving" -> 384
        else -> 512
    }

fun predictedOutputQuantile(
    responseMode: String,
    p50: Int,
    p90: Int,
    p95: Int,
): Int =
    when (responseMode.lowercase()) {
        "saving" -> p50
        "fast" -> p90
        "deep" -> p95
        else -> p90
    }.coerceAtLeast(1)

fun defaultDeadlineMillis(
    burden: ModelBurden,
    responseMode: String,
): Long {
    val base =
        when (burden) {
            ModelBurden.LIGHT -> 8_000L
            ModelBurden.STANDARD -> 16_000L
            ModelBurden.HEAVY -> 32_000L
            ModelBurden.RESTRICTED -> 40_000L
        }
    val multiplier =
        when (responseMode.lowercase()) {
            "fast" -> 0.72
            "saving" -> 0.90
            "deep" -> 1.45
            else -> 1.0
        }
    return (base * multiplier).toLong().coerceAtLeast(1_000L)
}
