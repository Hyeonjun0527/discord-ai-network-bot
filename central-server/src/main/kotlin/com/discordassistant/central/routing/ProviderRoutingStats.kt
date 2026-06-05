package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import org.springframework.stereotype.Component
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToInt

data class ProviderRoutingSnapshot(
    val recentHandled: Int = 0,
    val successRate: Double = 0.94,
    val timeoutRate: Double = 0.0,
    val latencyMillis: Long = 0,
    val outputChars: Int = 0,
    val sampleCount: Int = 0,
    val successCount: Int = 0,
    val goodputCount: Int = 0,
    val rawThroughputCount: Int = 0,
    val failureCount: Int = 0,
    val wasteTokens: Long = 0,
    val ttftMillis: Long = 0,
    val tbtMillis: Long = 0,
    val e2eMillis: Long = 0,
    val trustedConcurrency: Int = 1,
)

@Component
class ProviderRoutingStats {
    private val providers = ConcurrentHashMap<Long, MutableProviderStats>()

    fun snapshot(
        providerId: Long,
        burden: ModelBurden,
    ): ProviderRoutingSnapshot = providers[providerId]?.snapshot(burden) ?: ProviderRoutingSnapshot()

    fun recordSuccess(
        providerId: Long,
        burden: ModelBurden,
        latencyMillis: Long,
        outputChars: Int,
    ) {
        providers
            .computeIfAbsent(providerId) { MutableProviderStats() }
            .record(burden, success = true, timeout = false, latencyMillis = latencyMillis, outputChars = outputChars)
    }

    fun recordFailure(
        providerId: Long,
        burden: ModelBurden,
        latencyMillis: Long,
        timeout: Boolean,
    ) {
        providers
            .computeIfAbsent(providerId) { MutableProviderStats() }
            .record(burden, success = false, timeout = timeout, latencyMillis = latencyMillis, outputChars = null)
    }

    fun recordAttempt(
        providerId: Long,
        burden: ModelBurden,
        outcome: RoutingAttemptOutcome,
    ) {
        providers
            .computeIfAbsent(providerId) { MutableProviderStats() }
            .recordAttempt(burden, outcome)
    }

    private class MutableProviderStats {
        private val byBurden = EnumMap<ModelBurden, MutableBucketStats>(ModelBurden::class.java)

        @Synchronized
        fun snapshot(burden: ModelBurden): ProviderRoutingSnapshot = bucket(burden).snapshot()

        @Synchronized
        fun record(
            burden: ModelBurden,
            success: Boolean,
            timeout: Boolean,
            latencyMillis: Long,
            outputChars: Int?,
        ) {
            bucket(burden).record(success, timeout, latencyMillis, outputChars)
        }

        @Synchronized
        fun recordAttempt(
            burden: ModelBurden,
            outcome: RoutingAttemptOutcome,
        ) {
            bucket(burden).recordAttempt(outcome)
        }

        private fun bucket(burden: ModelBurden): MutableBucketStats = byBurden.getOrPut(burden) { MutableBucketStats() }
    }

    private class MutableBucketStats {
        private var successRate = 0.94
        private var timeoutRate = 0.0
        private var latencyMillis = 0.0
        private var outputChars = 0.0
        private var sampleCount = 0
        private var successCount = 0
        private var goodputCount = 0
        private var rawThroughputCount = 0
        private var failureCount = 0
        private var wasteTokens = 0L
        private var ttftMillis = 0.0
        private var tbtMillis = 0.0
        private var e2eMillis = 0.0
        private var recentHandled = 0.0
        private var recentUpdatedAtMillis = System.currentTimeMillis()

        fun snapshot(): ProviderRoutingSnapshot {
            val recent = decayedRecent(System.currentTimeMillis())
            return ProviderRoutingSnapshot(
                recentHandled = recent.roundToInt(),
                successRate = successRate.coerceIn(0.05, 0.99),
                timeoutRate = timeoutRate.coerceIn(0.0, 0.95),
                latencyMillis = latencyMillis.roundToInt().toLong(),
                outputChars = outputChars.roundToInt(),
                sampleCount = sampleCount,
                successCount = successCount,
                goodputCount = goodputCount,
                rawThroughputCount = rawThroughputCount,
                failureCount = failureCount,
                wasteTokens = wasteTokens,
                ttftMillis = ttftMillis.roundToInt().toLong(),
                tbtMillis = tbtMillis.roundToInt().toLong(),
                e2eMillis = e2eMillis.roundToInt().toLong(),
                trustedConcurrency = trustedConcurrency(),
            )
        }

        fun record(
            success: Boolean,
            timeout: Boolean,
            latencyMillis: Long,
            outputChars: Int?,
        ) {
            val now = System.currentTimeMillis()
            recentHandled = decayedRecent(now) + 1.0
            recentUpdatedAtMillis = now
            sampleCount += 1
            this.successRate = ema(this.successRate, if (success) 1.0 else 0.0)
            this.timeoutRate = ema(this.timeoutRate, if (timeout) 1.0 else 0.0)
            this.latencyMillis = ema(this.latencyMillis.takeIf { it > 0.0 } ?: latencyMillis.toDouble(), latencyMillis.toDouble())
            if (success && outputChars != null) {
                this.outputChars = ema(this.outputChars.takeIf { it > 0.0 } ?: outputChars.toDouble(), outputChars.toDouble())
            }
            if (success) {
                successCount += 1
                rawThroughputCount += 1
            } else {
                failureCount += 1
            }
        }

        fun recordAttempt(outcome: RoutingAttemptOutcome) {
            val outputChars = outcome.actualOutputTokens * 4
            val e2eMillis = outcome.latency.e2eMillis.coerceAtLeast(1L)
            val ttftMillis = outcome.latency.ttftMillis.coerceAtLeast(1L)
            val tbtMillis = outcome.latency.averageTbtMillis.coerceAtLeast(1L)
            record(
                success = outcome.success,
                timeout = outcome.failureType in timeoutTypes,
                latencyMillis = e2eMillis,
                outputChars = if (outcome.success) outputChars else null,
            )
            this.ttftMillis = ema(this.ttftMillis.takeIf { it > 0.0 } ?: ttftMillis.toDouble(), ttftMillis.toDouble())
            this.tbtMillis = ema(this.tbtMillis.takeIf { it > 0.0 } ?: tbtMillis.toDouble(), tbtMillis.toDouble())
            this.e2eMillis = ema(this.e2eMillis.takeIf { it > 0.0 } ?: e2eMillis.toDouble(), e2eMillis.toDouble())
            if (outcome.contributesGoodput) {
                goodputCount += 1
            } else {
                wasteTokens += (outcome.actualInputTokens + outcome.actualOutputTokens).toLong().coerceAtLeast(0L)
            }
        }

        private fun decayedRecent(nowMillis: Long): Double {
            val elapsed = (nowMillis - recentUpdatedAtMillis).coerceAtLeast(0).toDouble()
            return recentHandled * 0.5.pow(elapsed / RECENT_HALF_LIFE_MILLIS)
        }

        private fun ema(
            current: Double,
            next: Double,
        ): Double = current * (1.0 - EMA_ALPHA) + next * EMA_ALPHA

        private fun trustedConcurrency(): Int =
            when {
                goodputCount >= 12 && successRate >= 0.90 -> 4
                goodputCount >= 6 && successRate >= 0.82 -> 2
                else -> 1
            }

        companion object {
            private val timeoutTypes =
                setOf(
                    RoutingFailureType.CONNECTION_TIMEOUT,
                    RoutingFailureType.FIRST_TOKEN_TIMEOUT,
                    RoutingFailureType.MID_STREAM_TIMEOUT,
                    RoutingFailureType.END_TO_END_TIMEOUT,
                )
        }
    }

    companion object {
        private const val EMA_ALPHA = 0.22
        private const val RECENT_HALF_LIFE_MILLIS = 300_000.0
    }
}
