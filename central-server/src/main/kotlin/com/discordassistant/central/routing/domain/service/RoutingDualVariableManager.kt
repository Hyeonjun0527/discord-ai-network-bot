package com.discordassistant.central.routing.domain.service

import com.discordassistant.central.routing.domain.model.AttemptFinalState
import com.discordassistant.central.routing.domain.model.RoutingFailureType
import com.discordassistant.central.routing.domain.model.RoutingLambdas
import com.discordassistant.central.shared.ModelBurden
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.EnumMap

data class DualUpdateInput(
    val attemptId: String,
    val providerId: Long,
    val userId: Long,
    val requestClass: ModelBurden,
    val finalState: AttemptFinalState,
    val sloMet: Boolean,
    val qualityMet: Boolean,
    val countsForGoodput: Boolean,
    val quotaPressure: Double,
    val providerBurdenPressure: Double,
    val usefulServiceCost: Double,
    val failureType: RoutingFailureType = RoutingFailureType.NONE,
    val userWeight: Double = 1.0,
)

@Component
class RoutingDualVariableManager {
    private val log = LoggerFactory.getLogger(RoutingDualVariableManager::class.java)
    private val sloByClass = EnumMap<ModelBurden, MutableSloClass>(ModelBurden::class.java)
    private val providerLambdas = mutableMapOf<Long, MutableProviderLambdas>()
    private val userFairness = mutableMapOf<Long, MutableUserFairness>()
    private val processedAttemptIds = LinkedHashSet<String>()
    private var invalidInputCount = 0L

    @Synchronized
    fun snapshot(
        providerId: Long,
        userId: Long,
        requestClass: ModelBurden,
    ): RoutingLambdas {
        val provider = providerLambdas[providerId]
        val user = userFairness[userId]
        return RoutingLambdas(
            slo = clampLambda(sloByClass[requestClass]?.lambda ?: DEFAULT_SLO_LAMBDA),
            burden = clampLambda(provider?.burden ?: DEFAULT_PROVIDER_LAMBDA),
            quota = clampLambda(provider?.quota ?: DEFAULT_PROVIDER_LAMBDA),
            failure = clampLambda(provider?.failure ?: DEFAULT_FAILURE_LAMBDA),
            fairness = clampLambda(user?.lambda ?: 0.0),
        )
    }

    @Synchronized
    fun recordOutcome(input: DualUpdateInput): Boolean {
        if (!markAttemptProcessed(input.attemptId)) return false

        if (shouldUpdateSlo(input)) {
            val slo = sloByClass.computeIfAbsent(input.requestClass) { MutableSloClass() }
            slo.violationWindow.add(if (input.sloMet) 0.0 else 1.0)?.let { violationRate ->
                slo.lambda = clampLambda(slo.lambda + ETA_SLO * (violationRate - SLO_VIOLATION_TARGET), slo.lambda)
            }
        }

        val provider = providerLambdas.computeIfAbsent(input.providerId) { MutableProviderLambdas() }
        finitePressure(input.providerBurdenPressure, "providerBurdenPressure")?.let { pressure ->
            val burdenPressure = provider.burdenPressure.update(pressure, PRESSURE_EMA_ALPHA)
            provider.burden = clampLambda(provider.burden + ETA_PROVIDER * (burdenPressure - BURDEN_TARGET), provider.burden)
        }
        finitePressure(input.quotaPressure, "quotaPressure")?.let { pressure ->
            val quotaPressure = provider.quotaPressure.update(pressure, PRESSURE_EMA_ALPHA)
            provider.quota = clampLambda(provider.quota + ETA_PROVIDER * (quotaPressure - QUOTA_TARGET), provider.quota)
        }
        providerFailureSample(input)?.let { failed ->
            provider.failureWindow.add(failed)?.let { failureRate ->
                provider.failure = clampLambda(provider.failure + ETA_PROVIDER * (failureRate - FAILURE_TARGET), provider.failure)
            }
        }

        userFairness.values.forEach { it.service *= FAIRNESS_SERVICE_DECAY }
        val user = userFairness.computeIfAbsent(input.userId) { MutableUserFairness() }
        evictOldestUserFairness(input.userId)
        user.weight = input.userWeight.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        if (input.countsForGoodput && input.qualityMet) {
            user.service += input.usefulServiceCost.coerceAtLeast(0.0) / SERVICE_COST_SCALE
        }
        val totalService = userFairness.values.sumOf { it.service }
        val totalWeight = userFairness.values.sumOf { it.weight }.coerceAtLeast(1.0)
        val targetShare = totalService * (user.weight / totalWeight)
        val normalizedDebt = targetShare - user.service
        user.lambda = clampLambda(user.lambda + ETA_FAIRNESS * normalizedDebt, user.lambda)

        return true
    }

    @Synchronized
    fun invalidInputs(): Long = invalidInputCount

    private fun markAttemptProcessed(attemptId: String): Boolean {
        if (attemptId.isBlank()) return false
        if (!processedAttemptIds.add(attemptId)) return false
        if (processedAttemptIds.size > PROCESSED_ATTEMPT_LIMIT) {
            val iterator = processedAttemptIds.iterator()
            repeat(processedAttemptIds.size - PROCESSED_ATTEMPT_LIMIT) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        return true
    }

    /**
     * userFairness 는 사용자별 무제한 누적이라 processedAttemptIds 처럼 상한을 둔다(무한 증가 방지).
     * 상한 초과 시 가장 오래 전에 처음 본 사용자부터 제거하되, 방금 갱신한 현재 사용자는 보존한다.
     */
    private fun evictOldestUserFairness(currentUserId: Long) {
        if (userFairness.size <= USER_FAIRNESS_LIMIT) return
        var toRemove = userFairness.size - USER_FAIRNESS_LIMIT
        val iterator = userFairness.keys.iterator()
        while (toRemove > 0 && iterator.hasNext()) {
            if (iterator.next() != currentUserId) {
                iterator.remove()
                toRemove -= 1
            }
        }
    }

    private fun shouldUpdateSlo(input: DualUpdateInput): Boolean =
        input.finalState != AttemptFinalState.LOST_HEDGE &&
            input.failureType != RoutingFailureType.USER_CANCELLED

    private fun providerFailureSample(input: DualUpdateInput): Double? =
        when {
            input.finalState == AttemptFinalState.SUCCESS -> 0.0
            input.finalState == AttemptFinalState.LOST_HEDGE -> null
            input.failureType == RoutingFailureType.USER_CANCELLED -> null
            input.failureType == RoutingFailureType.CENTRAL_CANCELLED -> null
            input.finalState == AttemptFinalState.CANCELLED -> null
            input.finalState == AttemptFinalState.REJECTED_BY_PROVIDER -> 1.0
            input.failureType in providerCausedFailures -> 1.0
            input.finalState == AttemptFinalState.TIMEOUT -> 1.0
            input.finalState == AttemptFinalState.FAILED -> 1.0
            else -> null
        }

    private fun finitePressure(
        value: Double,
        fieldName: String,
    ): Double? =
        if (value.isFinite()) {
            value.coerceAtLeast(0.0)
        } else {
            invalidInputCount += 1
            log.warn("dual 변수 입력이 유한하지 않아 기존 값을 유지합니다: {}", fieldName)
            null
        }

    private class MutableSloClass {
        var lambda = DEFAULT_SLO_LAMBDA
        val violationWindow = RollingRateWindow(SLO_WINDOW_SIZE)
    }

    private class MutableProviderLambdas {
        var burden = DEFAULT_PROVIDER_LAMBDA
        var quota = DEFAULT_PROVIDER_LAMBDA
        var failure = DEFAULT_FAILURE_LAMBDA
        val burdenPressure = Ema()
        val quotaPressure = Ema()
        val failureWindow = RollingRateWindow(FAILURE_WINDOW_SIZE)
    }

    private class MutableUserFairness {
        var service = 0.0
        var lambda = 0.0
        var weight = 1.0
    }

    private class Ema {
        private var initialized = false
        private var value = 0.0

        fun update(
            sample: Double,
            alpha: Double,
        ): Double {
            value =
                if (initialized) {
                    alpha * sample + (1.0 - alpha) * value
                } else {
                    initialized = true
                    sample
                }
            return value
        }
    }

    private class RollingRateWindow(
        private val size: Int,
    ) {
        private var samples = 0
        private var positives = 0.0

        fun add(value: Double): Double? {
            samples += 1
            positives += value
            if (samples < size) return null
            val rate = positives / samples
            samples = 0
            positives = 0.0
            return rate
        }
    }

    companion object {
        private const val DEFAULT_SLO_LAMBDA = 0.20
        private const val DEFAULT_PROVIDER_LAMBDA = 0.06
        private const val DEFAULT_FAILURE_LAMBDA = 0.05
        private const val MAX_LAMBDA = 8.0
        private const val ETA_SLO = 0.08
        private const val ETA_PROVIDER = 0.06
        private const val ETA_FAIRNESS = 0.02
        private const val SLO_VIOLATION_TARGET = 0.05
        private const val BURDEN_TARGET = 0.75
        private const val QUOTA_TARGET = 0.70
        private const val FAILURE_TARGET = 0.05
        private const val PRESSURE_EMA_ALPHA = 0.20
        private const val SERVICE_COST_SCALE = 1_000.0
        private const val FAIRNESS_SERVICE_DECAY = 0.98
        private const val SLO_WINDOW_SIZE = 20
        private const val FAILURE_WINDOW_SIZE = 20
        private const val PROCESSED_ATTEMPT_LIMIT = 10_000
        private const val USER_FAIRNESS_LIMIT = 10_000

        private val providerCausedFailures =
            setOf(
                RoutingFailureType.PROVIDER_OFFLINE,
                RoutingFailureType.CONNECTION_TIMEOUT,
                RoutingFailureType.FIRST_TOKEN_TIMEOUT,
                RoutingFailureType.MID_STREAM_TIMEOUT,
                RoutingFailureType.END_TO_END_TIMEOUT,
                RoutingFailureType.QUOTA_EXCEEDED,
                RoutingFailureType.MODEL_ERROR,
                RoutingFailureType.INVALID_RESPONSE,
            )

        private fun clampLambda(
            value: Double,
            fallback: Double = 0.0,
        ): Double = if (value.isFinite()) value.coerceIn(0.0, MAX_LAMBDA) else fallback.coerceIn(0.0, MAX_LAMBDA)
    }
}
