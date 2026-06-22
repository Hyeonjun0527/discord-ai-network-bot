package com.discordassistant.central.participation.application.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 정책 drift 탐지기 테스트(NEXA-P19-T017). action/calibration/feature/server-slice drift 를 탐지하고,
 * **자동 재학습 대신 shadow 재평가·운영 알림**을 권고하는지 검증한다(acceptance T017).
 */
class PolicyDriftDetectorTest {
    private val detector = PolicyDriftDetector()

    private fun stableInput() =
        PolicyDriftInput(
            baselineActionDistribution = listOf(0.6, 0.1, 0.1, 0.15, 0.05),
            currentActionDistribution = listOf(0.6, 0.1, 0.1, 0.15, 0.05),
            baselineCalibrationError = 0.03,
            currentCalibrationError = 0.03,
            featureDistributions =
                mapOf(
                    "tempo" to FeatureDistributionDrift(listOf(0.5, 0.5), listOf(0.5, 0.5)),
                ),
            serverSlices =
                listOf(
                    ServerSliceDrift("tempo-low", 0.10, 0.10, 0.08, 0.08),
                ),
        )

    @Test
    fun `안정 분포면 drift 가 없고 권고는 NONE 이다`() {
        val report = detector.evaluate(stableInput())
        assertThat(report.hasDrift).isFalse()
        assertThat(report.worstSeverity).isEqualTo(DriftSeverity.NONE)
        assertThat(report.recommendedResponse).isEqualTo(DriftResponse.NONE)
    }

    @Test
    fun `action 분포 이동이 크면 critical drift 를 탐지한다`() {
        val input =
            stableInput().copy(
                // SPEAK 가 폭증(과발화로 이동) — 큰 PSI.
                currentActionDistribution = listOf(0.2, 0.05, 0.05, 0.65, 0.05),
            )
        val report = detector.evaluate(input)
        val action = report.signals.first { it.axis == DriftAxis.ACTION_DISTRIBUTION }
        assertThat(action.severity).isEqualTo(DriftSeverity.CRITICAL)
    }

    @Test
    fun `acceptance — critical drift 는 자동 재학습이 아니라 shadow 재평가와 운영 알림을 권고한다`() {
        val input =
            stableInput().copy(
                currentActionDistribution = listOf(0.2, 0.05, 0.05, 0.65, 0.05),
            )
        val report = detector.evaluate(input)
        assertThat(report.worstSeverity).isEqualTo(DriftSeverity.CRITICAL)
        // 자동 재학습/배포 항목은 enum 에 존재하지 않는다.
        assertThat(report.recommendedResponse).isEqualTo(DriftResponse.SHADOW_REEVALUATE_AND_ALERT)
        assertThat(DriftResponse.entries.map { it.name }).noneMatch { it.contains("RETRAIN") || it.contains("DEPLOY") }
    }

    @Test
    fun `calibration 악화가 크면 critical drift 를 탐지한다`() {
        val input = stableInput().copy(currentCalibrationError = 0.20)
        val report = detector.evaluate(input)
        val cal = report.signals.first { it.axis == DriftAxis.CALIBRATION }
        assertThat(cal.severity).isEqualTo(DriftSeverity.CRITICAL)
    }

    @Test
    fun `feature 분포 이동을 가장 큰 PSI 로 탐지한다`() {
        val input =
            stableInput().copy(
                featureDistributions =
                    mapOf(
                        "tempo" to FeatureDistributionDrift(listOf(0.5, 0.5), listOf(0.5, 0.5)),
                        "lang" to FeatureDistributionDrift(listOf(0.9, 0.1), listOf(0.1, 0.9)),
                    ),
            )
        val report = detector.evaluate(input)
        val feat = report.signals.first { it.axis == DriftAxis.FEATURE_DISTRIBUTION }
        assertThat(feat.severity).isEqualTo(DriftSeverity.CRITICAL)
    }

    @Test
    fun `한 서버 slice 의 성능 후퇴가 크면 warn 이상으로 탐지한다`() {
        val input =
            stableInput().copy(
                serverSlices =
                    listOf(
                        ServerSliceDrift("tempo-low", 0.10, 0.10, 0.08, 0.08),
                        // 한 slice 에서 FIR 가 baseline 대비 크게 상승(특정 서버 붕괴).
                        ServerSliceDrift("lang-ko", 0.10, 0.30, 0.08, 0.08),
                    ),
            )
        val report = detector.evaluate(input)
        val slice = report.signals.first { it.axis == DriftAxis.SERVER_SLICE_PERFORMANCE }
        assertThat(slice.severity).isEqualTo(DriftSeverity.CRITICAL)
    }

    @Test
    fun `PSI 는 같은 분포에서 0 에 가깝다`() {
        val psi =
            PolicyDriftDetector.populationStabilityIndex(
                listOf(0.4, 0.3, 0.3),
                listOf(0.4, 0.3, 0.3),
            )
        assertThat(psi).isLessThan(1e-6)
    }
}
