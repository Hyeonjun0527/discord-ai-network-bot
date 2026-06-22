package com.discordassistant.central.global.observability

import java.time.Duration

/**
 * 모델 오류·fallback alert 평가기(NEXA-P18-T012, 순수 평가 로직).
 *
 * policy timeout·schema mismatch·fallback-to-silent 의 **지속 시간/비율** 을 임계와 비교해 alert 를 낸다.
 *
 * **acceptance(T012) — fallback 자체가 정상 안전 동작일 수 있어 지속 시간/비율 기준을 사용한다**:
 *  - 단일 fallback 이벤트로는 절대 alert 하지 않는다 — fallback-to-silent 는 안전한 정상 동작일 수 있다.
 *  - alert 는 (1) **비율**(`failures/total` 이 임계 초과) **그리고** (2) **지속 시간**(임계 초과가 최소 기간 이상
 *    지속)을 함께 만족해야 발생한다. 잠깐의 spike 는 무시하고 지속적 열화만 잡는다.
 *  - 표본이 최소 수 미만이면 비율을 단정하지 않는다(noise 회피).
 *
 * 순수성: 표준 타입([Duration])만. Spring/JPA/JDA 미참조. 임계는 [NexaModelErrorThresholds] 로 주입.
 */
class NexaModelErrorAlertEvaluator(
    private val thresholds: NexaModelErrorThresholds = NexaModelErrorThresholds.DEFAULT,
) {
    /**
     * 한 윈도의 모델 오류 측정값을 평가해 alert 를 돌려준다(없으면 null). 표본 부족·짧은 지속·낮은 비율이면 정상.
     */
    fun evaluate(window: NexaModelErrorWindow): NexaModelErrorAlert? {
        if (window.totalRequests < thresholds.minSamples) return null
        val failureRatio = window.failureRatio()
        if (failureRatio < thresholds.failureRatioThreshold) return null
        if (window.sustainedFor < thresholds.minSustained) return null
        val response =
            if (failureRatio >= thresholds.criticalFailureRatio || window.sustainedFor >= thresholds.criticalSustained) {
                NexaAlertResponse.AUTO_DOWNGRADE
            } else {
                NexaAlertResponse.HUMAN_CONFIRM
            }
        return NexaModelErrorAlert(
            response = response,
            failureRatio = failureRatio,
            sustainedFor = window.sustainedFor,
        )
    }
}

/**
 * 한 평가 윈도의 모델 오류 측정값(집계). [policyTimeouts]+[schemaMismatches]+[fallbackToSilent] 가 실패로 집계되며,
 * [totalRequests] 가 분모, [sustainedFor] 는 임계 초과가 지속된 기간이다.
 */
data class NexaModelErrorWindow(
    val totalRequests: Long,
    val policyTimeouts: Long,
    val schemaMismatches: Long,
    val fallbackToSilent: Long,
    /** 실패 비율이 임계를 넘긴 채 지속된 기간(운영 집계가 채운다). */
    val sustainedFor: Duration,
) {
    init {
        require(totalRequests >= 0 && policyTimeouts >= 0 && schemaMismatches >= 0 && fallbackToSilent >= 0) {
            "오류 카운트는 음수일 수 없다"
        }
    }

    /** 실패 비율 [0,1] = (timeout+mismatch+fallback) / total. total 0 이면 0. */
    fun failureRatio(): Double {
        if (totalRequests <= 0L) return 0.0
        return (policyTimeouts + schemaMismatches + fallbackToSilent).toDouble() / totalRequests.toDouble()
    }
}

/** 발생한 모델 오류 alert 1건(집계 — 원문 비포함). */
data class NexaModelErrorAlert(
    val response: NexaAlertResponse,
    val failureRatio: Double,
    val sustainedFor: Duration,
)

/** 모델 오류 alert 임계(운영 튜닝). 비율 + 지속 시간 동시 기준(acceptance T012). */
data class NexaModelErrorThresholds(
    /** 비율을 단정하기 위한 최소 표본 수. */
    val minSamples: Long,
    /** alert 발생 실패 비율 임계. */
    val failureRatioThreshold: Double,
    /** alert 발생 최소 지속 기간. */
    val minSustained: Duration,
    /** 자동 강등으로 격상하는 실패 비율. */
    val criticalFailureRatio: Double,
    /** 자동 강등으로 격상하는 지속 기간. */
    val criticalSustained: Duration,
) {
    companion object {
        val DEFAULT =
            NexaModelErrorThresholds(
                minSamples = 20,
                failureRatioThreshold = 0.2,
                minSustained = Duration.ofMinutes(5),
                criticalFailureRatio = 0.5,
                criticalSustained = Duration.ofMinutes(15),
            )
    }
}
