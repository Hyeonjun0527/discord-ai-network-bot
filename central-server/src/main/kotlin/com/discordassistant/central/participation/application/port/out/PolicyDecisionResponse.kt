package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution

/**
 * 정책 결정 응답 계약(NEXA-P08-T007, application 레이어 값 객체·불변).
 *
 * 결정 엔진([ParticipationPolicyPort])이 돌려주는 **출력 계약** 이다. 단일 최종 답이 아니라 **확률분포** 로
 * 행동·대상·delay·socialAct·burst 를 운반하고, 불확실성([uncertainty])을 명시한다. participation 의 결정 결합
 * 단계(이후 task)가 seed 로 이 분포를 하나의 [com.discordassistant.central.participation.domain.model.action.SocialAction]
 * 으로 접는다.
 *
 * **acceptance(T007) — 단일 최종 답만 강제하지 않고 확률분포가 schema validation 을 통과한다**:
 * - [actionWeights] 는 행동 종류별 확률(합 = 1.0) — 하나로 강제하지 않는다.
 * - [targetDistribution]/[delayDistribution]/[burstProfile]/[socialActWeights] 모두 분포다.
 * - 각 분포는 자체 [init] 에서 확률 합·범위를 검증한다(JSON schema 미러:
 *   contracts/policy/policy-decision-response.schema.json).
 *
 * 순수성 경계: application 레이어 — 도메인 분포 타입과 표준 타입만. Spring/JPA/JDA 미참조.
 */
data class PolicyDecisionResponse(
    /** 행동 종류별 확률(IGNORE/WAIT/REACT/SPEAK/CANCEL_PENDING). 합 = 1.0 — [init] 검증. */
    val actionWeights: Map<SocialActionKind, Double>,
    /** 행동 대상 확률분포(message/member/thread/none). */
    val targetDistribution: ActionTargetDistribution,
    /** 발사 지연 구간 분포. */
    val delayDistribution: DelayDistribution,
    /** SPEAK 시 발화 종류 확률(social act 별). 합 = 1.0 — [init] 검증. UNKNOWN 허용. */
    val socialActWeights: Map<SocialAct, Double>,
    /** SPEAK 시 발화 형태 프로파일(조각 수·길이·간격·reaction-only). */
    val burstProfile: BurstProfile,
    /** 결정 불확실성 [0,1] — 높을수록 모델이 자신 없음(정책이 보수적으로 접을 근거). */
    val uncertainty: Double,
    /** 이 응답을 만든 모델 버전(추적·shadow 비교). */
    val modelVersion: String,
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
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
    }

    /** 가장 확률이 높은 행동 종류(동률이면 enum 선언 순서상 먼저). 분포를 단일 답으로 강제하진 않지만 argmax 편의. */
    val mostLikelyAction: SocialActionKind
        get() = SocialActionKind.entries.maxByOrNull { actionWeights[it] ?: 0.0 } ?: SocialActionKind.IGNORE

    companion object {
        const val EPSILON: Double = 1e-9
    }
}
