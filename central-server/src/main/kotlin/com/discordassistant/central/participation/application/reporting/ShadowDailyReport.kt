package com.discordassistant.central.participation.application.reporting

import java.time.LocalDate

/**
 * shadow **일일 리포트**(NEXA-P09-T021, application 레이어). 하루치 shadow 관찰을 정책별로 집계한 값 객체다 —
 * 예측량·발화 share·FIR/MIR proxy·오류·데이터 누락. 집계 수치만(원문·개별 사용자 미노출).
 *
 * **acceptance(T021) — 정책별 예측량, 발화 share, FIR/MIR proxy, 오류, 데이터 누락을 일 단위 집계**:
 * [perPolicy] 가 정책별 줄([PolicyDailyStat]), [errorCount]/[dataGapCount] 가 그날의 운영 건강 신호.
 *
 * 리포트 **생성 실패가 Discord ingestion 을 막지 않는다**(acceptance T021)는 어댑터 job 경계에서 보장한다 —
 * 이 값 객체 자체는 순수 집계라 부작용이 없다.
 */
data class ShadowDailyReport(
    /** 집계 대상 날짜(UTC 기준 일). */
    val date: LocalDate,
    /** 정책별 집계 줄(AlwaysSilent/Mention/FixedProb/Cooldown/BurstAware/legacy). */
    val perPolicy: List<PolicyDailyStat>,
    /** 그날 관찰/집계 중 발생한 오류 건수(운영 건강 — 비0 이면 주의). */
    val errorCount: Int,
    /** 그날 데이터 누락(예측은 있는데 관찰 창 미완 등) 건수. */
    val dataGapCount: Int,
) {
    init {
        require(errorCount >= 0) { "errorCount 는 음수일 수 없다" }
        require(dataGapCount >= 0) { "dataGapCount 는 음수일 수 없다" }
    }

    /** 그날 전체 예측량(정책별 합). */
    val totalPredictions: Long
        get() = perPolicy.sumOf { it.predictionCount }
}

/**
 * 한 정책의 하루 집계 줄(application 값 객체). 예측량·발화 share·FIR/MIR proxy. 집계 수치만.
 */
data class PolicyDailyStat(
    /** 정책/모델 버전(AlwaysSilent/Mention/… 식별). */
    val modelVersion: String,
    /** 그날 이 정책의 예측 수. */
    val predictionCount: Long,
    /** 이 정책의 SPEAK 샘플 비율 [0,1](발화 share). 예측 0 이면 null. */
    val speakShare: Double?,
    /** False Interruption proxy 비율 [0,1](T015). 표본 없으면 null. */
    val falseInterruptionRate: Double?,
    /** Missed Intervention proxy 비율 [0,1](T016). 표본 없으면 null. */
    val missedInterventionRate: Double?,
) {
    init {
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
        require(predictionCount >= 0) { "predictionCount 는 음수일 수 없다" }
    }
}
