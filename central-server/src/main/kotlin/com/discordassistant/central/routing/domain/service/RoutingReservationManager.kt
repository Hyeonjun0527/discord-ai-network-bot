package com.discordassistant.central.routing.domain.service

import com.discordassistant.central.routing.domain.model.AttemptFinalState
import com.discordassistant.central.routing.domain.model.predictedOutputQuantile
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class ProviderReservationSnapshot(
    val activeReservations: Int = 0,
    val reservedQuotaUnits: Int = 0,
    val pendingPrefillTokens: Int = 0,
    val pendingDecodeTokens: Int = 0,
    val pendingWorkMillis: Double = 0.0,
    val maxObservedActive: Int = 0,
)

data class RoutingReservation(
    val reservationId: String,
    val requestId: String,
    val providerId: Long,
    val reservedInputTokens: Int,
    val reservedOutputTokens: Int,
    val reservedQuotaUnits: Int,
    val estimatedWorkMillis: Double,
    val createdAtMillis: Long,
) {
    private val finalized = AtomicBoolean(false)

    fun tryMarkFinalized(): Boolean = finalized.compareAndSet(false, true)
}

sealed class ReservationResult {
    data class Reserved(
        val reservation: RoutingReservation,
    ) : ReservationResult()

    data class Rejected(
        val reason: String,
    ) : ReservationResult()
}

data class ReservationFinalization(
    val reservationId: String,
    val providerId: Long,
    val finalState: AttemptFinalState,
    val duplicate: Boolean,
    val activeReservationsAfter: Int,
    val reservedQuotaUnitsAfter: Int,
)

@Component
class RoutingReservationManager(
    private val filter: ProviderFilterPipeline = ProviderFilterPipeline(),
) {
    private val providers = ConcurrentHashMap<Long, MutableProviderReservationState>()
    private val reservations = ConcurrentHashMap<String, RoutingReservation>()

    fun snapshot(providerId: Long): ProviderReservationSnapshot = providers[providerId]?.snapshot() ?: ProviderReservationSnapshot()

    fun tryReserve(
        candidate: Candidate,
        ctx: RequestContext,
        nowMillis: Long = System.currentTimeMillis(),
    ): ReservationResult {
        val hardCheck = filter.filter(listOf(candidate), ctx)
        if (hardCheck.eligible.isEmpty()) {
            return ReservationResult.Rejected(hardCheck.dropped[candidate.providerId] ?: "HARD_CONSTRAINT_RECHECK_FAILED")
        }
        val state = providers.computeIfAbsent(candidate.providerId) { MutableProviderReservationState() }
        return state.reserve(candidate, ctx, nowMillis).also {
            if (it is ReservationResult.Reserved) reservations[it.reservation.reservationId] = it.reservation
        }
    }

    fun finalize(
        reservationId: String,
        finalState: AttemptFinalState,
    ): ReservationFinalization? {
        val reservation = reservations[reservationId] ?: return null
        val state = providers.computeIfAbsent(reservation.providerId) { MutableProviderReservationState() }
        val duplicate = !reservation.tryMarkFinalized()
        val snapshot =
            if (duplicate) {
                state.snapshot()
            } else {
                state.release(reservation)
            }
        if (!duplicate) reservations.remove(reservationId)
        return ReservationFinalization(
            reservationId = reservationId,
            providerId = reservation.providerId,
            finalState = finalState,
            duplicate = duplicate,
            activeReservationsAfter = snapshot.activeReservations,
            reservedQuotaUnitsAfter = snapshot.reservedQuotaUnits,
        )
    }

    private class MutableProviderReservationState {
        private val activeReservationIds = LinkedHashSet<String>()
        private var reservedQuotaUnits = 0
        private var pendingPrefillTokens = 0
        private var pendingDecodeTokens = 0
        private var pendingWorkMillis = 0.0
        private var maxObservedActive = 0

        @Synchronized
        fun reserve(
            candidate: Candidate,
            ctx: RequestContext,
            nowMillis: Long,
        ): ReservationResult {
            val active = maxOf(candidate.activeRequests, activeReservationIds.size)
            val limit = candidate.effectiveConcurrencyLimit()
            if (active >= limit) return ReservationResult.Rejected("CONCURRENCY_FULL")
            val reservedAfter = reservedQuotaUnits + ctx.quotaReservationUnits
            if (candidate.remainingDaily != Int.MAX_VALUE && reservedAfter > candidate.remainingDaily) {
                return ReservationResult.Rejected("QUOTA_INSUFFICIENT")
            }
            val outputTokens =
                predictedOutputQuantile(
                    ctx.responseMode,
                    ctx.predictedOutputP50,
                    ctx.predictedOutputP90,
                    ctx.predictedOutputP95,
                )
            val estimatedWork =
                candidate.estimatedPendingWorkMillis +
                    ctx.promptTokens / candidate.prefillTokensPerSecondEma.cleanRate() * 1_000.0 +
                    outputTokens / candidate.decodeTokensPerSecondEma.cleanRate() * 1_000.0
            val reservation =
                RoutingReservation(
                    reservationId = UUID.randomUUID().toString(),
                    requestId = ctx.requestId.ifBlank { UUID.randomUUID().toString() },
                    providerId = candidate.providerId,
                    reservedInputTokens = ctx.promptTokens,
                    reservedOutputTokens = outputTokens,
                    reservedQuotaUnits = ctx.quotaReservationUnits,
                    estimatedWorkMillis = estimatedWork.coerceAtLeast(1.0),
                    createdAtMillis = nowMillis,
                )
            activeReservationIds += reservation.reservationId
            reservedQuotaUnits += reservation.reservedQuotaUnits
            pendingPrefillTokens += reservation.reservedInputTokens
            pendingDecodeTokens += reservation.reservedOutputTokens
            pendingWorkMillis += reservation.estimatedWorkMillis
            maxObservedActive = maxOf(maxObservedActive, activeReservationIds.size)
            return ReservationResult.Reserved(reservation)
        }

        @Synchronized
        fun release(reservation: RoutingReservation): ProviderReservationSnapshot {
            activeReservationIds.remove(reservation.reservationId)
            reservedQuotaUnits = (reservedQuotaUnits - reservation.reservedQuotaUnits).coerceAtLeast(0)
            pendingPrefillTokens = (pendingPrefillTokens - reservation.reservedInputTokens).coerceAtLeast(0)
            pendingDecodeTokens = (pendingDecodeTokens - reservation.reservedOutputTokens).coerceAtLeast(0)
            pendingWorkMillis = (pendingWorkMillis - reservation.estimatedWorkMillis).coerceAtLeast(0.0)
            return snapshot()
        }

        @Synchronized
        fun snapshot(): ProviderReservationSnapshot =
            ProviderReservationSnapshot(
                activeReservations = activeReservationIds.size,
                reservedQuotaUnits = reservedQuotaUnits,
                pendingPrefillTokens = pendingPrefillTokens,
                pendingDecodeTokens = pendingDecodeTokens,
                pendingWorkMillis = pendingWorkMillis,
                maxObservedActive = maxObservedActive,
            )
    }
}

private fun Double.cleanRate(): Double = if (isFinite() && this > 0.0) this else 1.0
