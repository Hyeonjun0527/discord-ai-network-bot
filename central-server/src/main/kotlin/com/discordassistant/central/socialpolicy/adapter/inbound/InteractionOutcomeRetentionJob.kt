package com.discordassistant.central.socialpolicy.adapter.inbound

import com.discordassistant.central.socialpolicy.application.InteractionOutcomeRetentionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 만료된 행동-결과 관측 행을 매일 정리한다. */
@Component
@ConditionalOnProperty(
    prefix = "central.nexa.closed-loop-retention",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class InteractionOutcomeRetentionJob(
    private val retention: InteractionOutcomeRetentionService,
    @param:Value("\${central.nexa.closed-loop-retention.days:30}") private val retentionDays: Long,
) {
    private val log = LoggerFactory.getLogger(InteractionOutcomeRetentionJob::class.java)

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
            } while (purged == InteractionOutcomeRetentionService.BATCH_SIZE && batches < MAX_BATCHES_PER_RUN)
            if (total > 0) log.info("NEXA 행동-결과 관측 정리: {}건 삭제(보존 {}일)", total, retentionDays)
        } catch (e: RuntimeException) {
            log.warn("NEXA 행동-결과 관측 정리 실패(다음 주기 계속): {}", e.message, e)
        }
    }

    private companion object {
        const val MAX_BATCHES_PER_RUN: Int = 100
    }
}
