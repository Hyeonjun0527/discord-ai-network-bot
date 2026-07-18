package com.discordassistant.central.socialpolicy.application

import com.discordassistant.central.socialpolicy.adapter.outbound.persistence.ObservedInteractionOutcomeRepository
import com.discordassistant.central.socialpolicy.adapter.outbound.persistence.UnresolvedInteractionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 만료된 행동-결과 관측을 자식 outcome부터 지워 폐루프 경험 테이블의 보존 기간을 강제한다. */
@Service
class InteractionOutcomeRetentionService(
    private val interactions: UnresolvedInteractionRepository,
    private val outcomes: ObservedInteractionOutcomeRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun purgeExpired(
        retentionDays: Long,
        now: Instant = Instant.now(clock),
    ): Int {
        val cutoff = now.minus(Duration.ofDays(retentionDays.coerceAtLeast(1)))
        val expired = interactions.findTop1000ByExpiresAtBeforeOrderByExpiresAtAsc(cutoff)
        if (expired.isEmpty()) return 0
        val actionIds = expired.map { it.actionId }
        outcomes.deleteAllInBatch(outcomes.findByActionIdIn(actionIds))
        interactions.deleteAllInBatch(expired)
        return expired.size
    }

    companion object {
        const val BATCH_SIZE: Int = 1_000
    }
}
