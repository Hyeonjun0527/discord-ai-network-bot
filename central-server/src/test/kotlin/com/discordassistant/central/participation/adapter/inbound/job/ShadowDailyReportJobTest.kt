package com.discordassistant.central.participation.adapter.inbound.job

import com.discordassistant.central.participation.application.evaluation.InterventionProxyRates
import com.discordassistant.central.participation.application.port.out.ShadowDailyInputSource
import com.discordassistant.central.participation.application.port.out.ShadowDailyReportStorePort
import com.discordassistant.central.participation.application.reporting.PolicyDailyInput
import com.discordassistant.central.participation.application.reporting.ShadowDailyReport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * shadow 일일 리포트 job 어댑터 acceptance 단위 테스트(NEXA-P09-T021). 핵심: **리포트 실패가 ingestion 을 막지
 * 않는다**(예외를 throw 하지 않고 false 반환). 실제 cron 미등록 — [ShadowDailyReportJob.runDailyReport] 를 직접 호출.
 */
class ShadowDailyReportJobTest {
    private val date = LocalDate.parse("2026-06-22")

    private fun input() =
        PolicyDailyInput(
            modelVersion = "burst-aware",
            predictionCount = 10,
            speakCount = 3,
            proxyRates = InterventionProxyRates(10, 1, 2, 0.1, 0.2),
        )

    private class RecordingStore : ShadowDailyReportStorePort {
        var saved: ShadowDailyReport? = null

        override fun save(report: ShadowDailyReport) {
            saved = report
        }

        override fun findByDate(date: LocalDate): ShadowDailyReport? =
            saved
                ?.takeIf { it.date == date }
    }

    @Test
    fun `T021 — 정상 경로는 리포트를 집계해 저장한다`() {
        val store = RecordingStore()
        val job =
            ShadowDailyReportJob(
                inputSource = { listOf(input()) },
                reportStore = store,
            )
        val ok = job.runDailyReport(date)
        assertThat(ok).isTrue()
        assertThat(store.saved).isNotNull()
        val stat = store.saved!!.perPolicy.single()
        assertThat(stat.speakShare).isEqualTo(0.3)
    }

    @Test
    fun `T021 — 입력 수집 실패해도 throw 하지 않고 false 반환(ingestion 보호)`() {
        val throwingSource =
            ShadowDailyInputSource { error("입력 소스 폭발 — ingestion 은 영향받으면 안 됨") }
        val job = ShadowDailyReportJob(inputSource = throwingSource, reportStore = RecordingStore())
        val ok = job.runDailyReport(date)
        assertThat(ok).isFalse()
    }

    @Test
    fun `T021 — 저장 실패해도 throw 하지 않고 false 반환(ingestion 보호)`() {
        val throwingStore =
            object : ShadowDailyReportStorePort {
                override fun save(report: ShadowDailyReport) = error("저장 폭발")

                override fun findByDate(date: LocalDate): ShadowDailyReport? = null
            }
        val job = ShadowDailyReportJob(inputSource = { listOf(input()) }, reportStore = throwingStore)
        val ok = job.runDailyReport(date)
        assertThat(ok).isFalse()
    }
}
