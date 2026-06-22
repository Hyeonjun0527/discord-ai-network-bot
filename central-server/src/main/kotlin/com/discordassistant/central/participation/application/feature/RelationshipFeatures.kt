package com.discordassistant.central.participation.application.feature

import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue
import kotlin.math.exp

/**
 * relationship feature builder(NEXA-P08-T013, application 레이어·순수 함수). socialmemory(P06) 읽기 포트가 채운
 * 관계 집계에서 정책 feature 를 만든다 — familiarity, reciprocity, observed banter acceptance, 그리고 **표본 confidence**.
 *
 * **acceptance(T013) — 작은 표본 confidence 가 별도 feature 로 포함된다**:
 * [RelationshipObservation.sampleSize] 로부터 0→1 포화 곡선의 [REL_SAMPLE_CONFIDENCE] 를 **독립 feature** 로
 * 싣는다. 표본이 적으면 confidence 가 낮아, 정책이 familiarity/banter 값을 약하게 쓰도록 신호를 준다(작은 표본을
 * 성격/관계로 단정하지 않게 — observable-state-policy 와 일관).
 *
 * 관계 값이 아직 없으면(처음 보는 사용자) 모두 missing 으로 둘 수 있다([RelationshipObservation.observed] = false).
 *
 * participation 은 socialmemory 도메인 타입을 직접 import 하지 않고 읽기 포트가 채운 [RelationshipObservation] 만 본다.
 *
 * 순수성 경계: application 레이어 — 표준·kotlin.math 만. Spring/JPA/JDA 미참조.
 */
object RelationshipFeatures {
    /** 표본 confidence 포화 척도(>0). 이 표본 수 근처에서 confidence 가 충분히 1 에 접근. */
    const val DEFAULT_SAMPLE_SATURATION: Double = 8.0

    fun build(
        observation: RelationshipObservation,
        sampleSaturation: Double = DEFAULT_SAMPLE_SATURATION,
    ): Map<FeatureId, FeatureValue> {
        val valueOrMissing: (Double) -> FeatureValue = { v ->
            if (observation.observed) FeatureValue.present(v) else FeatureValue.MISSING
        }
        return linkedMapOf(
            FeatureCatalog.REL_FAMILIARITY to valueOrMissing(observation.familiarity),
            FeatureCatalog.REL_RECIPROCITY to valueOrMissing(observation.reciprocity),
            FeatureCatalog.REL_BANTER_ACCEPTANCE to valueOrMissing(observation.banterAcceptance),
            // sample confidence 는 표본 수 자체로 항상 계산 가능(별도 feature, acceptance T013).
            FeatureCatalog.REL_SAMPLE_CONFIDENCE to
                FeatureValue.present(
                    sampleConfidence(observation.sampleSize, sampleSaturation),
                ),
        )
    }

    /**
     * 표본 수 → confidence [0,1] 포화 곡선(1 - exp(-n / scale)). 표본 0 이면 0(모름), 많을수록 1 에 수렴.
     * 작은 표본을 강하게 쓰지 않도록 정책에 주는 별도 신호다.
     */
    fun sampleConfidence(
        sampleSize: Int,
        saturation: Double = DEFAULT_SAMPLE_SATURATION,
    ): Double {
        require(saturation > 0.0) { "saturation 은 양수여야 한다" }
        if (sampleSize <= 0) return 0.0
        return (1.0 - exp(-sampleSize.toDouble() / saturation)).coerceIn(0.0, 1.0)
    }
}

/**
 * 관계 관찰 입력 뷰(application 값 객체). socialmemory(P06) 읽기 포트가 채운다 — 집계 수치만(원문·식별자 추론 없음).
 * [observed] = false 면 관계 값이 아직 없는 것(처음 보는 사용자)이라 값 feature 는 missing 으로 둔다.
 */
data class RelationshipObservation(
    /** 친밀도 집계 [0,1](P06 FamiliarityCalculator 결과). */
    val familiarity: Double,
    /** 상호성 집계 [0,1](P06 InteractionReciprocity). */
    val reciprocity: Double,
    /** 관찰된 농담 수용 비율 [0,1](P06 ObservedBanterAcceptance — 성격 라벨 아님). */
    val banterAcceptance: Double,
    /** 이 관계 추정의 관찰 표본 수(작을수록 confidence 낮음). */
    val sampleSize: Int,
    /** 관계 값이 관측됐는가 — false 면 값 feature 는 missing(처음 보는 사용자). */
    val observed: Boolean,
) {
    init {
        require(familiarity in 0.0..1.0) { "familiarity 는 [0,1] 범위여야 한다" }
        require(reciprocity in 0.0..1.0) { "reciprocity 는 [0,1] 범위여야 한다" }
        require(banterAcceptance in 0.0..1.0) { "banterAcceptance 는 [0,1] 범위여야 한다" }
        require(sampleSize >= 0) { "sampleSize 는 음수일 수 없다" }
    }
}
