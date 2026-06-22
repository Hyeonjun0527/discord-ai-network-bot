package com.discordassistant.central.participation.domain.model.decision

import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind

/**
 * 행동 확률분포 aggregate(NEXA-P08, 순수 도메인 값 객체·불변). 결정 엔진이 낸 "어떤 행동을 할지" 의 분포를 **도메인
 * 타입만으로** 묶는다 — action kind 가중치, 행동 대상([ActionTargetDistribution]), 지연([DelayDistribution]),
 * socialAct 가중치, 발화 형태([BurstProfile]), 불확실성.
 *
 * application 계약([com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse])이
 * 외부 어댑터(ONNX/gRPC)와 공유하는 와이어 형태라면, 이 aggregate 는 **도메인 서비스**(샘플러·calibration·안전
 * 후처리)가 application 에 의존하지 않고 다룰 수 있는 순수 표현이다(module-dag.md: domain 은 application 미참조).
 *
 * 검증은 application [PolicyDecisionResponse] 와 동일 규칙(확률 합·범위)을 [init] 에서 재적용한다.
 *
 * 순수성: Spring/JPA/JDA·application 미참조. 도메인 타입만.
 */
data class ActionDistribution(
    /** 행동 종류별 확률(IGNORE/WAIT/REACT/SPEAK/CANCEL_PENDING). 합 = 1.0 — [init] 검증. */
    val actionWeights: Map<SocialActionKind, Double>,
    /** 행동 대상 확률분포. */
    val targetDistribution: ActionTargetDistribution,
    /** 발사 지연 구간 분포. */
    val delayDistribution: DelayDistribution,
    /** SPEAK 시 발화 종류 확률(합 = 1.0, 비면 미정). */
    val socialActWeights: Map<SocialAct, Double>,
    /** SPEAK 시 발화 형태 프로파일. */
    val burstProfile: BurstProfile,
    /** 결정 불확실성 [0,1]. */
    val uncertainty: Double,
) {
    init {
        require(actionWeights.isNotEmpty()) { "actionWeights 는 비어 있을 수 없다" }
        actionWeights.forEach { (kind, p) ->
            require(p in 0.0..1.0) { "$kind 확률은 [0,1] 범위여야 한다: $p" }
        }
        require(kotlin.math.abs(actionWeights.values.sum() - 1.0) <= EPSILON) {
            "actionWeights 합은 1.0 이어야 한다: 합=${actionWeights.values.sum()}"
        }
        socialActWeights.forEach { (act, p) ->
            require(p in 0.0..1.0) { "$act 확률은 [0,1] 범위여야 한다: $p" }
        }
        if (socialActWeights.isNotEmpty()) {
            require(kotlin.math.abs(socialActWeights.values.sum() - 1.0) <= EPSILON) {
                "socialActWeights 합은 1.0 이어야 한다: 합=${socialActWeights.values.sum()}"
            }
        }
        require(uncertainty in 0.0..1.0) { "uncertainty 는 [0,1] 범위여야 한다: $uncertainty" }
    }

    /** 가장 확률이 높은 행동 종류(동률이면 enum 선언 순서상 먼저). */
    val mostLikelyAction: SocialActionKind
        get() = SocialActionKind.entries.maxByOrNull { actionWeights[it] ?: 0.0 } ?: SocialActionKind.IGNORE

    /** actionWeights 만 교체한 복제(calibration·안전 후처리의 재정규화 결과 반영용). */
    fun withActionWeights(weights: Map<SocialActionKind, Double>): ActionDistribution = copy(actionWeights = weights)

    companion object {
        const val EPSILON: Double = 1e-9
    }
}
