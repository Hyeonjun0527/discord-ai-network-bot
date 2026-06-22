package com.discordassistant.central.participation.adapter.inbound.job

import com.discordassistant.central.participation.application.port.out.ShadowDailyInputSource
import com.discordassistant.central.participation.application.port.out.ShadowDailyReportStorePort
import com.discordassistant.central.participation.application.reporting.ShadowDailyReport
import com.discordassistant.central.participation.application.reporting.ShadowDailyReportService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * shadow 일일 리포트 **스케줄 job 어댑터**(NEXA-P09-T021, inbound adapter). 전날치 정책별 집계 입력을 모아
 * [ShadowDailyReportService] 로 리포트를 만들고 store 에 저장한다.
 *
 * **acceptance(T021) — 리포트 생성 실패가 Discord ingestion 을 막지 않는다**: [runDailyReport] 는 **모든 예외를
 * 삼킨다**(잡고 로깅만) — 입력 수집/집계/저장 어디서 실패해도 throw 하지 않으므로, 같은 프로세스의 Discord
 * ingestion 경로에 영향이 없다. 반환 boolean 으로 성공 여부만 알린다(테스트·관찰용).
 *
 * **실제 cron 미등록(제약)**: 이 컴포넌트는 `central.shadow.daily-report.enabled=true` 일 때만 빈으로 등록된다
 * (`@ConditionalOnProperty`, 기본 false=미등록). 따라서 운영 스케줄러에 자동 등록되지 않고, 테스트는 [runDailyReport]
 * 를 직접 호출해 검증한다(코드+단위테스트까지 — 실제 cron 스케줄 등록 금지).
 */
@Component
@ConditionalOnProperty(prefix = "central.shadow.daily-report", name = ["enabled"], havingValue = "true")
class ShadowDailyReportJob(
    private val inputSource: ShadowDailyInputSource,
    private val reportStore: ShadowDailyReportStorePort,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ShadowDailyReportJob::class.java)

    /**
     * 전날(UTC) 리포트를 생성·저장한다. 실패해도 throw 하지 않는다(ingestion 보호). 반환: 성공 여부.
     */
    @Scheduled(cron = "\${central.shadow.daily-report.cron:0 30 0 * * *}", zone = "UTC")
    fun scheduledRun() {
        runDailyReport(LocalDate.now(clock).minusDays(1))
    }

    /**
     * [date] 의 일일 리포트를 만들어 저장한다. **모든 예외를 잡아 로깅만** 한다 — ingestion 을 막지 않는다.
     * 반환: 저장 성공이면 true, 어떤 단계에서든 실패하면 false.
     */
    fun runDailyReport(date: LocalDate): Boolean =
        try {
            val inputs = inputSource.collectFor(date)
            val report: ShadowDailyReport =
                ShadowDailyReportService.build(
                    date = date,
                    inputs = inputs,
                    errorCount = 0,
                    dataGapCount = 0,
                )
            reportStore.save(report)
            true
        } catch (ex: RuntimeException) {
            // acceptance T021: 리포트 실패가 ingestion 을 막지 않는다 — 잡아서 로깅만.
            log.warn("shadow daily report 생성 실패(ingestion 영향 없음): date={}", date, ex)
            false
        }
}
