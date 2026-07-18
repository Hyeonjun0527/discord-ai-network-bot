package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.adapter.outbound.persistence.WaitReevaluationOutboxRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 만료된 WAIT 재평가 outbox 행을 유한 보존해 폐루프 운영 테이블의 무제한 증가를 막는다. */
@Service
class WaitReevaluationRetentionService(
    private val outbox: WaitReevaluationOutboxRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun purgeExpired(
        retentionDays: Long,
        now: Instant = Instant.now(clock),
    ): Int {
        val cutoff = now.minus(Duration.ofDays(retentionDays.coerceAtLeast(1)))
        val expired = outbox.findTop1000ByExpiresAtBeforeOrderByExpiresAtAsc(cutoff)
        if (expired.isEmpty()) return 0
        outbox.deleteAllInBatch(expired)
        return expired.size
    }

    companion object {
        const val BATCH_SIZE: Int = 1_000
    }
}
