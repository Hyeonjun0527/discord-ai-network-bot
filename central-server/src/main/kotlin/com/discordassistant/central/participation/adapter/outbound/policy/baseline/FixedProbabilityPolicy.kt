package com.discordassistant.central.participation.adapter.outbound.policy.baseline

import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.domain.model.action.SocialActionKind

/**
 * 고정 확률로 IGNORE/REACT/SPEAK 를 샘플링하는 **기준선**(NEXA-P09-T003). 입력 맥락을 보지 않고 항상 같은
 * [actionWeights] 분포를 낸다 — 실제 접기(샘플링)는 participation 의 [com.discordassistant.central.participation.domain.service.SeededPolicySampler]
 * 가 결정 seed 로 수행한다(분포→단일 행동). 즉 이 정책은 "분포를 고정"하고, seed 재현·calibration 은 그 분포에서 나온다.
 *
 * **acceptance(T003) — seed 재현과 확률 calibration sanity test 가 있다**:
 * - **seed 재현**: 분포가 입력·seed 와 무관하게 고정이고, 샘플러가 결정론이라 같은 seed=같은 행동(테스트로 증명).
 * - **calibration sanity**: 많은 seed 로 샘플링한 행동의 경험 분포가 고정 확률에 수렴한다(테스트로 증명).
 *
 * speechAllowed=false 면 SPEAK 확률을 IGNORE 로 흡수해 계약 안전(분포 밖 발화 금지)을 지킨다.
 */
class FixedProbabilityPolicy(
    private val ignoreProbability: Double = DEFAULT_IGNORE,
    private val reactProbability: Double = DEFAULT_REACT,
    private val speakProbability: Double = DEFAULT_SPEAK,
) : BaselinePolicy() {
    init {
        require(ignoreProbability in 0.0..1.0) { "ignoreProbability 는 [0,1] 범위여야 한다: $ignoreProbability" }
        require(reactProbability in 0.0..1.0) { "reactProbability 는 [0,1] 범위여야 한다: $reactProbability" }
        require(speakProbability in 0.0..1.0) { "speakProbability 는 [0,1] 범위여야 한다: $speakProbability" }
        require(kotlin.math.abs(ignoreProbability + reactProbability + speakProbability - 1.0) <= EPSILON) {
            "확률 합은 1.0 이어야 한다: ${ignoreProbability + reactProbability + speakProbability}"
        }
    }

    override fun decide(request: PolicyDecisionRequest): PolicyDecisionResponse {
        // speechAllowed=false 면 SPEAK 확률을 IGNORE 로 흡수(발화 게이트 닫힘 — 계약 안전).
        val speak = if (request.config.speechAllowed) speakProbability else 0.0
        val ignore = ignoreProbability + (speakProbability - speak)
        val weights =
            mapOf(
                SocialActionKind.IGNORE to ignore,
                SocialActionKind.REACT to reactProbability,
                SocialActionKind.SPEAK to speak,
            ).filterValues { it > 0.0 }
        return BaselineDistributions.response(
            actionWeights = weights,
            modelVersion = MODEL_VERSION,
            // 고정 확률은 맥락 정보가 없으므로 불확실성이 본질적으로 높다(보수적 신호).
            uncertainty = 1.0 - (weights.values.maxOrNull() ?: 0.0),
        )
    }

    companion object {
        /** 결정 추적·shadow 비교용 안정 모델 버전 식별자. */
        const val MODEL_VERSION: String = "baseline-fixed-probability-1"
        private const val EPSILON = 1e-9

        /** 기본 고정 확률(대부분 침묵, 가끔 리액션, 드물게 발화) — 단순 대조군 기본값. */
        const val DEFAULT_IGNORE = 0.7
        const val DEFAULT_REACT = 0.2
        const val DEFAULT_SPEAK = 0.1
    }
}
