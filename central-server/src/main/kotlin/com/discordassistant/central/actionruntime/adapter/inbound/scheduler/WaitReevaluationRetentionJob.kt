package com.discordassistant.central.actionruntime.adapter.inbound.scheduler

import com.discordassistant.central.actionruntime.application.WaitReevaluationRetentionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** WAIT 재평가 outbox의 보존 기간 초과 행을 매일 정리한다. */
@Component
@ConditionalOnProperty(
    prefix = "central.nexa.closed-loop-retention",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class WaitReevaluationRetentionJob(
    private val retention: WaitReevaluationRetentionService,
    @param:Value("\${central.nexa.closed-loop-retention.days:30}") private val retentionDays: Long,
) {
    private val log = LoggerFactory.getLogger(WaitReevaluationRetentionJob::class.java)

    @Scheduled(cron = "\${central.nexa.closed-loop-retention.cron:0 55 3 * * *}", zone = "UTC")
    fun scheduledPurge() {
        try {
            var total = 0
            var batches = 0
            var purged: Int
            do {
                purged = retention.purgeExpired(retentionDays)
                total += purged
                batches++
            } while (purged == WaitReevaluationRetentionService.BATCH_SIZE && batches < MAX_BATCHES_PER_RUN)
            if (total > 0) log.info("WAIT 재평가 outbox 정리: {}건 삭제(보존 {}일)", total, retentionDays)
        } catch (e: RuntimeException) {
            log.warn("WAIT 재평가 outbox 정리 실패(다음 주기 계속): {}", e.message, e)
        }
    }

    private companion object {
        const val MAX_BATCHES_PER_RUN: Int = 100
    }
}
