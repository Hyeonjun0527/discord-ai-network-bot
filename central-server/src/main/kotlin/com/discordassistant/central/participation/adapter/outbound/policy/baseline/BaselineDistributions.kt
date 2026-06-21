package com.discordassistant.central.participation.adapter.outbound.policy.baseline

import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution

/**
 * baseline 정책 공용 분포 빌더(NEXA-P09-T001~T005). baseline 후보들이 [PolicyDecisionResponse] 를 만들 때
 * **계약 규칙**(actionWeights 합=1.0, none 대상, 단일 조각 burst)을 한곳에서 지켜 중복·drift 를 막는다.
 *
 * baseline 은 의도적으로 단순한(때로 나쁜) 하한/대조군이라, target/delay/burst 분포는 **계약을 통과하는 최소 형태**
 * (대상 없음·즉시·한 줄)로 고정한다. baseline 의 차별점은 오직 [actionWeights] 분포다 — 정책 간 비교가 깨끗하다.
 *
 * 순수성 경계: adapter 레이어지만 도메인 분포 타입과 application 계약만 만진다(Spring/JPA/JDA 미참조 — 결정론).
 */
internal object BaselineDistributions {
    /** baseline 공용 대상 분포: 특정 대상 없음(채널 전체). baseline 은 대상 추론을 하지 않는다. */
    private val NONE_TARGET: ActionTargetDistribution = ActionTargetDistribution.none(resolverVersion = "baseline-1")

    /** baseline 공용 burst: 한 줄(형태만, 텍스트 없음). baseline 은 발화 형태를 다루지 않는다. */
    private val SINGLE_LINE_BURST: BurstProfile = BurstProfile.singleLine()

    /** 즉시 발사 분포(baseline 은 delay 추론을 하지 않는다). */
    private val IMMEDIATE_DELAY: DelayDistribution = DelayDistribution.IMMEDIATE

    /**
     * [actionWeights] 분포로 baseline 응답을 만든다. 나머지 차원(target/delay/burst/socialAct)은 계약 통과용
     * 최소 형태로 고정한다. socialActWeights 는 비워 둔다 — baseline 은 발화 종류를 정하지 않는다(미정).
     */
    fun response(
        actionWeights: Map<SocialActionKind, Double>,
        modelVersion: String,
        uncertainty: Double = 0.0,
        delayDistribution: DelayDistribution = IMMEDIATE_DELAY,
    ): PolicyDecisionResponse =
        PolicyDecisionResponse(
            actionWeights = actionWeights,
            targetDistribution = NONE_TARGET,
            delayDistribution = delayDistribution,
            socialActWeights = emptyMap(),
            burstProfile = SINGLE_LINE_BURST,
            uncertainty = uncertainty,
            modelVersion = modelVersion,
        )

    /** IGNORE 1.0 분포(완전 침묵 하한). */
    val ALWAYS_IGNORE: Map<SocialActionKind, Double> = mapOf(SocialActionKind.IGNORE to 1.0)

    /** SPEAK 1.0 분포(즉시 발화). */
    val ALWAYS_SPEAK: Map<SocialActionKind, Double> = mapOf(SocialActionKind.SPEAK to 1.0)
}
