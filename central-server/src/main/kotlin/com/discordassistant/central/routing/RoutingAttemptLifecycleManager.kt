package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class RoutingAttempt(
    val attemptId: String,
    val requestId: String,
    val providerId: Long,
    val reservationId: String,
    val userId: Long,
    val requestClass: ModelBurden,
    val startedAtNanos: Long,
    val dispatchAtNanos: Long,
    val estimatedInputTokens: Int,
    val estimatedOutputTokens: Int,
) {
    private val finalized = AtomicBoolean(false)

    fun tryFinalize(): Boolean = finalized.compareAndSet(false, true)
}

data class AttemptFinalizationResult(
    val attemptId: String,
    val providerId: Long,
    val finalState: AttemptFinalState,
    val duplicate: Boolean,
    val goodput: Boolean,
)

@Component
class RoutingAttemptLifecycleManager(
    private val reservationManager: RoutingReservationManager,
    private val stats: ProviderRoutingStats,
    private val duals: RoutingDualVariableManager,
    private val auditLogger: RoutingAuditLogger = RoutingAuditLogger(),
) {
    private val attempts = ConcurrentHashMap<String, RoutingAttempt>()

    fun startAttempt(
        ctx: RequestContext,
        reservation: RoutingReservation,
        dispatchAtNanos: Long = System.nanoTime(),
    ): RoutingAttempt {
        val attempt =
            RoutingAttempt(
                attemptId = UUID.randomUUID().toString(),
                requestId = ctx.requestId.ifBlank { reservation.requestId },
                providerId = reservation.providerId,
                reservationId = reservation.reservationId,
                userId = ctx.userId,
                requestClass = ctx.requiredBurden,
                startedAtNanos = dispatchAtNanos,
                dispatchAtNanos = dispatchAtNanos,
                estimatedInputTokens = reservation.reservedInputTokens,
                estimatedOutputTokens = reservation.reservedOutputTokens,
            )
        attempts[attempt.attemptId] = attempt
        return attempt
    }

    fun finalizeAttempt(
        attempt: RoutingAttempt,
        outcome: RoutingAttemptOutcome,
        quotaPressure: Double,
        providerBurdenPressure: Double,
    ): AttemptFinalizationResult {
        val duplicate = !attempt.tryFinalize()
        if (duplicate) {
            auditLogger.recordDuplicateFinalization(attempt.requestId, attempt.attemptId, outcome.finalState)
            return AttemptFinalizationResult(attempt.attemptId, attempt.providerId, outcome.finalState, duplicate = true, goodput = false)
        }
        reservationManager.finalize(attempt.reservationId, outcome.finalState)
        stats.recordAttempt(attempt.providerId, attempt.requestClass, outcome)
        duals.recordOutcome(
            DualUpdateInput(
                providerId = attempt.providerId,
                userId = attempt.userId,
                requestClass = attempt.requestClass,
                sloMet = outcome.sloMet,
                success = outcome.success,
                quotaPressure = quotaPressure,
                providerBurdenPressure = providerBurdenPressure,
                usefulServiceCost = (outcome.actualInputTokens * 0.4 + outcome.actualOutputTokens).coerceAtLeast(0.0),
            ),
        )
        attempts.remove(attempt.attemptId)
        auditLogger.recordAttemptFinalization(attempt.requestId, attempt.attemptId, outcome)
        return AttemptFinalizationResult(
            attemptId = attempt.attemptId,
            providerId = attempt.providerId,
            finalState = outcome.finalState,
            duplicate = false,
            goodput = outcome.contributesGoodput,
        )
    }
}
