package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import org.springframework.stereotype.Component
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class DualUpdateInput(
    val providerId: Long,
    val userId: Long,
    val requestClass: ModelBurden,
    val sloMet: Boolean,
    val success: Boolean,
    val quotaPressure: Double,
    val providerBurdenPressure: Double,
    val usefulServiceCost: Double,
)

@Component
class RoutingDualVariableManager {
    private val sloByClass = EnumMap<ModelBurden, Double>(ModelBurden::class.java)
    private val providerLambdas = ConcurrentHashMap<Long, MutableProviderLambdas>()
    private val userFairness = ConcurrentHashMap<Long, MutableUserFairness>()

    @Synchronized
    fun snapshot(
        providerId: Long,
        userId: Long,
        requestClass: ModelBurden,
    ): RoutingLambdas {
        val provider = providerLambdas.computeIfAbsent(providerId) { MutableProviderLambdas() }
        val user = userFairness.computeIfAbsent(userId) { MutableUserFairness() }
        return RoutingLambdas(
            slo = (sloByClass[requestClass] ?: DEFAULT_SLO_LAMBDA).clampLambda(),
            burden = provider.burden.clampLambda(),
            quota = provider.quota.clampLambda(),
            failure = provider.failure.clampLambda(),
            fairness = user.lambda.clampLambda(),
        )
    }

    @Synchronized
    fun recordOutcome(input: DualUpdateInput) {
        val sloGap = if (input.sloMet) -SLO_TARGET_SLACK else 1.0 - SLO_VIOLATION_TARGET
        sloByClass[input.requestClass] = ((sloByClass[input.requestClass] ?: DEFAULT_SLO_LAMBDA) + ETA_SLO * sloGap).clampLambda()

        val provider = providerLambdas.computeIfAbsent(input.providerId) { MutableProviderLambdas() }
        provider.burden = (provider.burden + ETA_PROVIDER * (input.providerBurdenPressure - BURDEN_TARGET)).clampLambda()
        provider.quota = (provider.quota + ETA_PROVIDER * (input.quotaPressure - QUOTA_TARGET)).clampLambda()
        provider.failure = (provider.failure + ETA_PROVIDER * ((if (input.success) 0.0 else 1.0) - FAILURE_TARGET)).clampLambda()

        val totalBefore = userFairness.values.sumOf { it.service }
        val countBefore = userFairness.size.coerceAtLeast(1)
        val targetShare = totalBefore / countBefore
        val user = userFairness.computeIfAbsent(input.userId) { MutableUserFairness() }
        if (input.success && input.sloMet) {
            user.service += input.usefulServiceCost.coerceAtLeast(0.0)
        }
        val debt = (targetShare - user.service).coerceAtLeast(0.0)
        user.lambda = (user.lambda + ETA_FAIRNESS * debt - ETA_FAIRNESS_DECAY * abs(user.service - targetShare)).clampLambda()
    }

    private class MutableProviderLambdas {
        var burden = DEFAULT_PROVIDER_LAMBDA
        var quota = DEFAULT_PROVIDER_LAMBDA
        var failure = DEFAULT_PROVIDER_LAMBDA
    }

    private class MutableUserFairness {
        var service = 0.0
        var lambda = 0.0
    }

    companion object {
        private const val DEFAULT_SLO_LAMBDA = 0.20
        private const val DEFAULT_PROVIDER_LAMBDA = 0.06
        private const val MAX_LAMBDA = 8.0
        private const val ETA_SLO = 0.08
        private const val ETA_PROVIDER = 0.06
        private const val ETA_FAIRNESS = 0.02
        private const val ETA_FAIRNESS_DECAY = 0.005
        private const val SLO_VIOLATION_TARGET = 0.05
        private const val SLO_TARGET_SLACK = 0.02
        private const val BURDEN_TARGET = 0.75
        private const val QUOTA_TARGET = 0.70
        private const val FAILURE_TARGET = 0.05

        fun clamp(value: Double): Double = value.clampLambda()

        private fun Double.clampLambda(): Double = if (isFinite()) coerceIn(0.0, MAX_LAMBDA) else 0.0
    }
}

private fun Double.clampLambda(): Double = RoutingDualVariableManager.clamp(this)
