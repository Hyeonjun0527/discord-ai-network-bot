package com.discordassistant.central.participation.application.monitoring

import kotlin.math.abs
import kotlin.math.ln

/**
 * 정책 drift 탐지기(NEXA-P19-T017, application 레이어·순수 평가 로직).
 *
 * LIVE 정책의 **행동 분포가 시간에 따라 drift** 하는지 baseline(승인 시점 분포)과 현재 창을 비교해 탐지한다
 * (deliverable T017). 4개 축을 본다: action distribution(PSI), calibration(ECE 변화), feature distribution(PSI),
 * server slice 성능 변화(FIR/MIR proxy). 순수 함수다 — 외부 호출·전송 없이 입력 측정값만으로 판정한다(실제
 * 알림 발송·shadow 재평가 시작은 운영/어댑터 책임).
 *
 * **acceptance(T017) — drift 시 자동 재학습 대신 shadow 재평가와 운영 알림을 시작한다**:
 * 결과 [PolicyDriftReport.recommendedResponse] 는 **절대 자동 재학습/배포가 아니다** — drift 가 critical 이면
 * [DriftResponse.SHADOW_REEVALUATE_AND_ALERT](shadow 재평가 + 운영 알림), warn 이면
 * [DriftResponse.HUMAN_REVIEW](사람 확인), 정상이면 [DriftResponse.NONE]. 자동 학습 트리거는 이 평가기에
 * 존재하지 않는다(P19-T009 ADR·human_gate 일관). 알림·shadow 재평가 발동은 사람/운영 게이트 뒤에 둔다.
 *
 * 순수성: 표준 타입·kotlin.math 만. Spring/JPA/JDA 미참조(participation application 경계).
 */
class PolicyDriftDetector(
    private val thresholds: DriftThresholds = DriftThresholds.DEFAULT,
) {
    /**
     * baseline 대비 현재 창의 drift 를 4개 축으로 평가한다. 각 축은 자기 신호를 임계와 비교해 [DriftSignal] 을
     * 내고, 전체 [recommendedResponse] 는 가장 심각한 축을 따른다(critical>warn>none). 정상이면 빈 신호 목록.
     */
    fun evaluate(input: PolicyDriftInput): PolicyDriftReport {
        val signals =
            buildList {
                add(
                    classify(
                        DriftAxis.ACTION_DISTRIBUTION,
                        populationStabilityIndex(input.baselineActionDistribution, input.currentActionDistribution),
                        thresholds.psiWarn,
                        thresholds.psiCritical,
                    ),
                )
                add(
                    classify(
                        DriftAxis.CALIBRATION,
                        abs(input.currentCalibrationError - input.baselineCalibrationError),
                        thresholds.calibrationDeltaWarn,
                        thresholds.calibrationDeltaCritical,
                    ),
                )
                add(
                    classify(
                        DriftAxis.FEATURE_DISTRIBUTION,
                        input.featureDistributions.maxOfOrNull { (_, dist) ->
                            populationStabilityIndex(dist.baseline, dist.current)
                        } ?: 0.0,
                        thresholds.psiWarn,
                        thresholds.psiCritical,
                    ),
                )
                add(
                    classify(
                        DriftAxis.SERVER_SLICE_PERFORMANCE,
                        worstSliceRegression(input.serverSlices),
                        thresholds.sliceRegressionWarn,
                        thresholds.sliceRegressionCritical,
                    ),
                )
            }
        return PolicyDriftReport(signals)
    }

    private fun classify(
        axis: DriftAxis,
        value: Double,
        warn: Double,
        critical: Double,
    ): DriftSignal {
        val severity =
            when {
                value >= critical -> DriftSeverity.CRITICAL
                value >= warn -> DriftSeverity.WARN
                else -> DriftSeverity.NONE
            }
        return DriftSignal(axis = axis, severity = severity, observedValue = value)
    }

    /**
     * 한 서버 slice 의 성능 후퇴 — proxy(FIR/MIR) 가 baseline 대비 얼마나 나빠졌는지(증가분). 여러 slice 중
     * 최악(가장 큰 후퇴)을 본다(평균이 좋아도 특정 서버가 무너지는 것을 잡는다).
     */
    private fun worstSliceRegression(slices: List<ServerSliceDrift>): Double =
        slices.maxOfOrNull { slice ->
            val firRise = (slice.currentFalseInterruptionRate - slice.baselineFalseInterruptionRate)
            val mirRise = (slice.currentMissedInterventionRate - slice.baselineMissedInterventionRate)
            maxOf(firRise, mirRise)
        } ?: 0.0

    companion object {
        /**
         * Population Stability Index. 두 분포(같은 bin)의 변화량 — Σ (cur-base)·ln(cur/base). 0 에 가까울수록
         * 안정, 커질수록 분포 이동(drift). 0 셀은 작은 eps 로 보호(로그/0분모 방지).
         */
        fun populationStabilityIndex(
            baseline: List<Double>,
            current: List<Double>,
        ): Double {
            require(baseline.size == current.size && baseline.isNotEmpty()) {
                "baseline·current 분포는 같은 길이의 비어 있지 않은 bin 이어야 한다"
            }
            val eps = 1e-6
            val baseSum = baseline.sum().coerceAtLeast(eps)
            val curSum = current.sum().coerceAtLeast(eps)
            var psi = 0.0
            for (i in baseline.indices) {
                val b = (baseline[i] / baseSum).coerceAtLeast(eps)
                val c = (current[i] / curSum).coerceAtLeast(eps)
                psi += (c - b) * ln(c / b)
            }
            return psi
        }
    }
}

/** drift 평가 입력(집계 분포·proxy 만 — 원문/개별 사용자 미포함). */
data class PolicyDriftInput(
    /** 승인 시점 action 분포(IGNORE/WAIT/REACT/SPEAK/CANCEL 비율, bin 합 ~1). */
    val baselineActionDistribution: List<Double>,
    /** 현재 창 action 분포(같은 bin). */
    val currentActionDistribution: List<Double>,
    /** baseline calibration error(ECE) [0,1]. */
    val baselineCalibrationError: Double,
    /** 현재 calibration error(ECE) [0,1]. */
    val currentCalibrationError: Double,
    /** feature 별 분포(이름 → baseline/current bin). 가장 큰 PSI 를 본다. */
    val featureDistributions: Map<String, FeatureDistributionDrift>,
    /** 서버 slice 별 성능(FIR/MIR proxy) baseline/current. */
    val serverSlices: List<ServerSliceDrift>,
)

/** 한 feature 의 분포 drift 입력(같은 bin baseline/current). */
data class FeatureDistributionDrift(
    val baseline: List<Double>,
    val current: List<Double>,
)

/** 한 서버 slice 의 성능 proxy drift(가명 slice — 집계만). */
data class ServerSliceDrift(
    /** slice 식별 가명(예: tempo-low / lang-ko). */
    val sliceKey: String,
    val baselineFalseInterruptionRate: Double,
    val currentFalseInterruptionRate: Double,
    val baselineMissedInterventionRate: Double,
    val currentMissedInterventionRate: Double,
) {
    init {
        require(sliceKey.isNotBlank()) { "sliceKey 는 비어 있을 수 없다" }
    }
}

/** drift 축(저카디널리티). */
enum class DriftAxis {
    ACTION_DISTRIBUTION,
    CALIBRATION,
    FEATURE_DISTRIBUTION,
    SERVER_SLICE_PERFORMANCE,
}

/** drift 심각도. */
enum class DriftSeverity {
    NONE,
    WARN,
    CRITICAL,
}

/** 한 축의 drift 신호(감사·대시보드 표시). */
data class DriftSignal(
    val axis: DriftAxis,
    val severity: DriftSeverity,
    /** 임계 비교에 쓰인 측정값(PSI·ECE delta·후퇴분). */
    val observedValue: Double,
)

/**
 * drift 평가 결과. 가장 심각한 신호에 따라 권고 response 를 정한다 — **자동 재학습은 없다**(acceptance T017).
 */
data class PolicyDriftReport(
    val signals: List<DriftSignal>,
) {
    /** 가장 심각한 신호 severity. */
    val worstSeverity: DriftSeverity
        get() =
            when {
                signals.any { it.severity == DriftSeverity.CRITICAL } -> DriftSeverity.CRITICAL
                signals.any { it.severity == DriftSeverity.WARN } -> DriftSeverity.WARN
                else -> DriftSeverity.NONE
            }

    /** drift 신호가 하나라도 임계를 넘었는가(warn 이상). */
    val hasDrift: Boolean
        get() = worstSeverity != DriftSeverity.NONE

    /**
     * 권고 response — **절대 자동 재학습/배포가 아니다**(acceptance T017):
     *  - CRITICAL → [DriftResponse.SHADOW_REEVALUATE_AND_ALERT](shadow 재평가 + 운영 알림).
     *  - WARN → [DriftResponse.HUMAN_REVIEW](사람 확인).
     *  - NONE → [DriftResponse.NONE](정상).
     */
    val recommendedResponse: DriftResponse
        get() =
            when (worstSeverity) {
                DriftSeverity.CRITICAL -> DriftResponse.SHADOW_REEVALUATE_AND_ALERT
                DriftSeverity.WARN -> DriftResponse.HUMAN_REVIEW
                DriftSeverity.NONE -> DriftResponse.NONE
            }
}

/**
 * drift 발생 시 권고 response(acceptance T017). **자동 재학습/자동 배포 항목은 존재하지 않는다** — 사람/운영
 * 게이트 뒤의 shadow 재평가·알림·사람 확인뿐이다.
 */
enum class DriftResponse {
    /** drift 없음 — 조치 불필요. */
    NONE,

    /** warn — 사람이 보고 판단(자동 조치 없음). */
    HUMAN_REVIEW,

    /** critical — shadow 재평가를 시작하고 운영에 알린다(자동 재학습 아님). */
    SHADOW_REEVALUATE_AND_ALERT,
}

/** drift 탐지 임계(운영 보수값). PSI·calibration delta·slice 후퇴 모두 warn/critical 2단계. */
data class DriftThresholds(
    val psiWarn: Double,
    val psiCritical: Double,
    val calibrationDeltaWarn: Double,
    val calibrationDeltaCritical: Double,
    val sliceRegressionWarn: Double,
    val sliceRegressionCritical: Double,
) {
    companion object {
        /** 보수적 기본값(PSI 0.1/0.25 는 업계 통례, calibration·slice 후퇴는 절대 비율). */
        val DEFAULT =
            DriftThresholds(
                psiWarn = 0.10,
                psiCritical = 0.25,
                calibrationDeltaWarn = 0.05,
                calibrationDeltaCritical = 0.10,
                sliceRegressionWarn = 0.05,
                sliceRegressionCritical = 0.10,
            )
    }
}
