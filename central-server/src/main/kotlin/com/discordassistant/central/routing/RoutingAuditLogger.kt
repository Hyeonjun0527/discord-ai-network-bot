package com.discordassistant.central.routing

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

data class RoutingAuditRecord(
    val requestId: String,
    val selectedProviderId: Long?,
    val candidateProviderIds: List<Long>,
    val infeasibleProviderReasons: Map<Long, String>,
    val scoreBreakdowns: List<RoutingScoreBreakdown>,
    val quotaReservationId: String?,
    val fallbackReason: String?,
    val decisionTimestampMillis: Long,
    val events: List<String>,
)

@Component
class RoutingAuditLogger {
    private val records = ConcurrentHashMap<String, MutableRoutingAuditRecord>()

    fun recordDecision(
        requestId: String,
        selectedProviderId: Long?,
        candidateProviderIds: List<Long>,
        infeasibleProviderReasons: Map<Long, String>,
        scoreBreakdowns: List<RoutingScoreBreakdown>,
        fallbackReason: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val mutable =
            records.computeIfAbsent(requestId.ifBlank { "unknown" }) {
                MutableRoutingAuditRecord(requestId.ifBlank { "unknown" })
            }
        synchronized(mutable) {
            mutable.selectedProviderId = selectedProviderId
            mutable.candidateProviderIds = candidateProviderIds
            mutable.infeasibleProviderReasons = infeasibleProviderReasons
            mutable.scoreBreakdowns = scoreBreakdowns
            mutable.fallbackReason = fallbackReason
            mutable.decisionTimestampMillis = nowMillis
        }
    }

    fun recordReservation(
        requestId: String,
        reservation: RoutingReservation,
    ) {
        append(requestId, "reservation:${reservation.reservationId}:provider:${reservation.providerId}") {
            it.quotaReservationId = reservation.reservationId
        }
    }

    fun recordReservationRejected(
        requestId: String,
        providerId: Long,
        reason: String,
    ) {
        append(requestId, "reservation_rejected:$providerId:$reason")
    }

    fun recordAttemptFinalization(
        requestId: String,
        attemptId: String,
        outcome: RoutingAttemptOutcome,
    ) {
        append(requestId, "attempt_finalized:$attemptId:${outcome.finalState}:goodput:${outcome.contributesGoodput}")
    }

    fun recordDuplicateFinalization(
        requestId: String,
        attemptId: String,
        finalState: AttemptFinalState,
    ) {
        append(requestId, "duplicate_finalization:$attemptId:$finalState")
    }

    fun recordFallback(
        requestId: String,
        providerId: Long,
        reason: String,
    ) {
        append(requestId, "fallback_exclude:$providerId:$reason") {
            it.fallbackReason = reason
        }
    }

    fun read(requestId: String): RoutingAuditRecord? = records[requestId]?.snapshot()

    private fun append(
        requestId: String,
        event: String,
        mutate: (MutableRoutingAuditRecord) -> Unit = {},
    ) {
        val mutable =
            records.computeIfAbsent(requestId.ifBlank { "unknown" }) {
                MutableRoutingAuditRecord(requestId.ifBlank { "unknown" })
            }
        synchronized(mutable) {
            mutable.events += event
            mutate(mutable)
        }
    }

    private class MutableRoutingAuditRecord(
        val requestId: String,
    ) {
        var selectedProviderId: Long? = null
        var candidateProviderIds: List<Long> = emptyList()
        var infeasibleProviderReasons: Map<Long, String> = emptyMap()
        var scoreBreakdowns: List<RoutingScoreBreakdown> = emptyList()
        var quotaReservationId: String? = null
        var fallbackReason: String? = null
        var decisionTimestampMillis: Long = System.currentTimeMillis()
        var events: List<String> = emptyList()

        fun snapshot(): RoutingAuditRecord =
            RoutingAuditRecord(
                requestId = requestId,
                selectedProviderId = selectedProviderId,
                candidateProviderIds = candidateProviderIds,
                infeasibleProviderReasons = infeasibleProviderReasons,
                scoreBreakdowns = scoreBreakdowns,
                quotaReservationId = quotaReservationId,
                fallbackReason = fallbackReason,
                decisionTimestampMillis = decisionTimestampMillis,
                events = events,
            )
    }
}
