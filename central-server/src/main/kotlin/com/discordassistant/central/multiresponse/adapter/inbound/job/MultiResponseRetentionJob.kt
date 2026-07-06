package com.discordassistant.central.multiresponse.adapter.inbound.job

import com.discordassistant.central.multiresponse.application.MultiResponseRetentionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * multi-response 관측 행 보존 정리 **스케줄 job 어댑터**. 보존 기간(`central.multiresponse.retention-days`,
 * 기본 30일)을 지난 run/candidate/synthesis 를 주기적으로 삭제해 테이블 무제한 증가를 막는다.
 *
 * 기본 활성(`central.multiresponse.retention.enabled`, 기본 true — 보존 기간이 넉넉해 안전)이며 `false` 로
 * 끄면 빈이 등록되지 않는다([ConditionalOnProperty]). 정리 실패가 다른 스케줄/요청을 막지 않도록 **모든
 * 런타임 예외를 삼켜** 로깅만 한다(다음 주기에서 재시도).
 */
@Component
@ConditionalOnProperty(
    prefix = "central.multiresponse.retention",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class MultiResponseRetentionJob(
    private val retention: MultiResponseRetentionService,
    @param:Value("\${central.multiresponse.retention-days:30}") private val retentionDays: Long,
) {
    private val log = LoggerFactory.getLogger(MultiResponseRetentionJob::class.java)

    /** 기본 매일 03:45(UTC) 보존 기간 초과 관측 행을 정리한다. 실패해도 throw 하지 않는다. */
    @Scheduled(cron = "\${central.multiresponse.retention.cron:0 45 3 * * *}", zone = "UTC")
    fun scheduledPurge() {
        try {
            val purged = retention.purgeExpired(retentionDays)
            if (purged > 0) log.info("multi-response 관측 정리: {}건 삭제(보존 {}일)", purged, retentionDays)
        } catch (e: RuntimeException) {
            log.warn("multi-response 관측 정리 실패(다음 주기 계속): {}", e.message, e)
        }
    }
}
