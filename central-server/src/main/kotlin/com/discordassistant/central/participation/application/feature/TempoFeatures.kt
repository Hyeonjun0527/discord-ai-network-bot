package com.discordassistant.central.participation.application.feature

import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue

/**
 * channel tempo feature builder(NEXA-P08-T012, application 레이어·순수 함수). 채널 템포를 정책 feature 로
 * 계산한다 — human burst rate, median gap, overlap ratio, NEXA share.
 *
 * **acceptance(T012) — 봇/옵트아웃 제외 규칙이 P06 과 동일하다**:
 * P06(socialmemory)이 봇·옵트아웃(동의 없음) 참여자를 관찰 집계에서 제외하는 것과 동일하게, 이 빌더도
 * [TempoParticipant.includeInHumanAggregate](= 사람 && 동의 && NEXA 아님)가 false 인 참여자를 **human 집계
 * (burst rate·median gap·overlap)에서 제외** 한다. NEXA 자신의 발화는 별도 [TEMPO_NEXA_SHARE] 로만 집계한다
 * (사람 템포에 NEXA 가 섞이지 않게).
 *
 * participation 은 conversation/socialmemory 도메인을 직접 import 하지 않고 읽기 포트가 채운 입력만 본다.
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA 미참조.
 */
object TempoFeatures {
    fun build(observation: TempoObservation): Map<FeatureId, FeatureValue> {
        // P06 동일 규칙: 봇/옵트아웃 제외 — human 집계에 포함되는 참여자만.
        val humans = observation.participants.filter { it.includeInHumanAggregate }
        val humanBurstCount = humans.sumOf { it.burstCount }
        val nexaBurstCount = observation.participants.filter { it.isNexa }.sumOf { it.burstCount }
        val totalBurstCount = observation.participants.sumOf { it.burstCount }

        val windowMinutes = observation.windowSeconds.coerceAtLeast(1.0) / 60.0
        val humanBurstRate = humanBurstCount.toDouble() / windowMinutes

        // NEXA share: 전체 burst 중 NEXA 비율(봇/옵트아웃 분모 포함 — NEXA 가 얼마나 점유하는지의 관찰).
        val nexaShare = if (totalBurstCount == 0) 0.0 else nexaBurstCount.toDouble() / totalBurstCount.toDouble()

        return linkedMapOf(
            FeatureCatalog.TEMPO_HUMAN_BURST_RATE to FeatureValue.present(humanBurstRate),
            FeatureCatalog.TEMPO_MEDIAN_GAP_SECONDS to gapFeature(observation.humanMedianGapSeconds),
            FeatureCatalog.TEMPO_OVERLAP_RATIO to FeatureValue.present(observation.humanOverlapRatio),
            FeatureCatalog.TEMPO_NEXA_SHARE to FeatureValue.present(nexaShare),
        )
    }

    /** median gap 은 human burst 가 1개 이하면 정의 불가 → missing 보존(0 으로 뭉개지 않음). */
    private fun gapFeature(humanMedianGapSeconds: Double?): FeatureValue =
        humanMedianGapSeconds?.let { FeatureValue.present(it) } ?: FeatureValue.MISSING
}

/**
 * channel tempo 관찰 입력 뷰(application 값 객체). 읽기 포트가 채운다. human 집계(rate/gap/overlap)는 봇/옵트아웃
 * 제외 후 값을 미리 계산해 넘기되, rate/share 는 참여자별 burst 카운트로 빌더가 P06 동일 규칙을 재적용한다.
 */
data class TempoObservation(
    /** 참여자별 burst 카운트와 제외 플래그(봇/옵트아웃/NEXA). */
    val participants: List<TempoParticipant>,
    /** 관측 창 길이(초). burst rate 의 분모. */
    val windowSeconds: Double,
    /** 봇/옵트아웃 제외 후 human burst 시작 간 median gap(초). human burst ≤1 이면 null(정의 불가 → missing). */
    val humanMedianGapSeconds: Double?,
    /** 봇/옵트아웃 제외 후 human burst 간 overlap 비율 [0,1]. */
    val humanOverlapRatio: Double,
) {
    init {
        require(windowSeconds >= 0.0) { "windowSeconds 는 음수일 수 없다" }
        require(humanOverlapRatio in 0.0..1.0) { "humanOverlapRatio 는 [0,1] 범위여야 한다" }
        humanMedianGapSeconds?.let { require(it >= 0.0) { "humanMedianGapSeconds 는 음수일 수 없다" } }
    }
}

/**
 * tempo 집계의 한 참여자(application 값 객체). [includeInHumanAggregate] 가 P06 동일 제외 규칙의 결과다
 * (사람 && 동의 && NEXA 아님). NEXA 자신은 [isNexa] 로 share 집계에만 쓰인다.
 */
data class TempoParticipant(
    val burstCount: Int,
    /** P06 동일 규칙: 봇/옵트아웃/NEXA 제외 후 human 집계 포함 여부. */
    val includeInHumanAggregate: Boolean,
    /** NEXA 자신인가 — NEXA share 집계용(human 집계에는 미포함). */
    val isNexa: Boolean = false,
) {
    init {
        require(burstCount >= 0) { "burstCount 는 음수일 수 없다" }
        require(!(includeInHumanAggregate && isNexa)) { "NEXA 는 human 집계에 포함될 수 없다(P06 동일 규칙)" }
    }
}
