package com.discordassistant.central.participation.application.reporting

import com.discordassistant.central.participation.application.evaluation.InterventionProxyRates
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * shadow 일일 리포트 집계 서비스 acceptance 단위 테스트(NEXA-P09-T021). 순수 집계 — 결정론·재현.
 */
class ShadowDailyReportServiceTest {
    private val date = LocalDate.parse("2026-06-22")

    private fun rates(
        n: Int,
        fi: Int,
        mi: Int,
    ) = InterventionProxyRates(
        sampleCount = n,
        falseInterruptionCount = fi,
        missedInterventionCount = mi,
        falseInterruptionRate = if (n == 0) null else fi.toDouble() / n,
        missedInterventionRate = if (n == 0) null else mi.toDouble() / n,
    )

    @Test
    fun `T021 — 정책별 예측량 발화 share FIR MIR 오류 데이터누락을 집계`() {
        val report =
            ShadowDailyReportService.build(
                date = date,
                inputs =
                    listOf(
                        PolicyDailyInput("always-silent", predictionCount = 100, speakCount = 0, proxyRates = rates(100, 0, 4)),
                        PolicyDailyInput("burst-aware", predictionCount = 100, speakCount = 30, proxyRates = rates(100, 6, 1)),
                    ),
                errorCount = 2,
                dataGapCount = 1,
            )
        assertThat(report.date).isEqualTo(date)
        assertThat(report.totalPredictions).isEqualTo(200)
        assertThat(report.errorCount).isEqualTo(2)
        assertThat(report.dataGapCount).isEqualTo(1)

        val silent = report.perPolicy.single { it.modelVersion == "always-silent" }
        assertThat(silent.speakShare).isCloseTo(0.0, within(1e-9))
        assertThat(silent.missedInterventionRate).isCloseTo(0.04, within(1e-9))

        val burst = report.perPolicy.single { it.modelVersion == "burst-aware" }
        assertThat(burst.speakShare).isCloseTo(0.3, within(1e-9))
        assertThat(burst.falseInterruptionRate).isCloseTo(0.06, within(1e-9))
    }

    @Test
    fun `T021 — 예측 0인 정책은 speak share null(단정 금지)`() {
        val report =
            ShadowDailyReportService.build(
                date = date,
                inputs = listOf(PolicyDailyInput("idle", predictionCount = 0, speakCount = 0, proxyRates = rates(0, 0, 0))),
                errorCount = 0,
                dataGapCount = 0,
            )
        assertThat(report.perPolicy.single().speakShare).isNull()
    }

    @Test
    fun `T021 — 결정론 - 같은 입력이면 같은 리포트`() {
        val inputs = listOf(PolicyDailyInput("p", predictionCount = 10, speakCount = 3, proxyRates = rates(10, 1, 2)))
        val a = ShadowDailyReportService.build(date, inputs, 0, 0)
        val b = ShadowDailyReportService.build(date, inputs, 0, 0)
        assertThat(a).isEqualTo(b)
    }
}
