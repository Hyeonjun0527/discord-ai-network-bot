package com.discordassistant.central.participation.application.reporting

import com.discordassistant.central.participation.application.evaluation.InterventionProxyRates
import java.time.LocalDate

/**
 * shadow 일일 리포트 **집계 서비스**(NEXA-P09-T021, application 레이어·순수). 정책별 입력 묶음([PolicyDailyInput])을
 * [ShadowDailyReport] 로 접는다 — 발화 share·FIR/MIR proxy 비율 계산은 이미 만든 evaluation 집계를 재사용한다.
 *
 * 이 서비스는 **순수 집계**라 부작용이 없다(I/O·스케줄 없음). 실제 저장·스케줄·실패 격리는 어댑터 job 이 한다
 * (acceptance T021: 리포트 생성 실패가 Discord ingestion 을 막지 않음). 그래서 테스트가 재현 가능·결정론이다.
 *
 * 순수성: application — 표준 타입·evaluation 값 객체만. Spring/JPA/JDA 미참조.
 */
object ShadowDailyReportService {
    /**
     * 하루치 정책별 입력으로 일일 리포트를 만든다. [errorCount]/[dataGapCount] 는 그날 관찰 파이프라인이 보고한
     * 운영 건강 신호(집계 입력으로 받음 — 이 순수 서비스는 직접 관측하지 않는다).
     */
    fun build(
        date: LocalDate,
        inputs: List<PolicyDailyInput>,
        errorCount: Int,
        dataGapCount: Int,
    ): ShadowDailyReport =
        ShadowDailyReport(
            date = date,
            perPolicy =
                inputs.map { input ->
                    PolicyDailyStat(
                        modelVersion = input.modelVersion,
                        predictionCount = input.predictionCount,
                        speakShare =
                            if (input.predictionCount == 0L) {
                                null
                            } else {
                                input.speakCount.toDouble() / input.predictionCount
                            },
                        falseInterruptionRate = input.proxyRates.falseInterruptionRate,
                        missedInterventionRate = input.proxyRates.missedInterventionRate,
                    )
                },
            errorCount = errorCount,
            dataGapCount = dataGapCount,
        )
}

/**
 * 한 정책의 하루 집계 입력(application 값 객체). 예측 수·SPEAK 샘플 수·proxy 비율 묶음. 집계 수치만.
 */
data class PolicyDailyInput(
    val modelVersion: String,
    /** 그날 이 정책의 예측 수. */
    val predictionCount: Long,
    /** 그 중 SPEAK 샘플 수(발화 share 분자). */
    val speakCount: Long,
    /** 이 정책의 proxy 비율(InterventionProxies.aggregate 결과). */
    val proxyRates: InterventionProxyRates,
) {
    init {
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
        require(predictionCount >= 0) { "predictionCount 는 음수일 수 없다" }
        require(speakCount in 0..predictionCount) { "speakCount 는 [0, predictionCount] 범위여야 한다" }
    }
}
