package com.discordassistant.central.participation.adapter.outbound.policy.onnx

import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayBucket
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import kotlin.math.ln

/**
 * ONNX head 출력 → [PolicyDecisionResponse] 디코더(NEXA-P11-T018, adapter 레이어).
 *
 * **deliverable(T018) — response 분포를 복원한다**: ONNX 모델의 5개 head softmax 출력을 도메인 분포로 되돌린다.
 * head category 순서는 ML SSOT(contracts/policy/fixtures/parity golden 의 categoryOrder, datasets.py)와 **정확히
 * 동일** 해야 분포가 뒤섞이지 않는다(T019 parity 가 이 순서 일치를 강제):
 *
 * | head | 인덱스 순서 → 도메인 |
 * | --- | --- |
 * | action | ignore/wait/react/speak/cancel → [SocialActionKind] |
 * | delay | 0-2s/2-10s/10-60s/60s+/never → [DelayBucket] IMMEDIATE/SHORT/MEDIUM/LONG/NEVER |
 * | act | acknowledge/agree/ask/tease/self_disclose/unknown → [SocialAct] |
 * | burst | none/single/multi → fragmentCountWeights(1/2 조각) + reaction-only(none) |
 * | target | 후보 슬롯 softmax → uncertainty 신호만(장면 target ID 부재라 none 대상) |
 *
 * **target 처리**: ONNX target head 는 추상 후보 슬롯 점수라 실제 장면 message/member/thread ID 가 없다. 계약
 * (ActionTargetDistribution 은 실재 ID 만 후보) 위반을 막기 위해 **none 대상**(채널 전체)으로 복원한다 — 구체 대상
 * 결정은 장면 context 를 가진 상위 단계 책임이다(adapter 는 행동/지연/형태/발화종류 분포만 복원).
 *
 * **uncertainty**: action 분포의 정규화 엔트로피(0=확신, 1=균등). 모델이 자신 없을수록 정책이 보수적으로 접도록.
 *
 * 순수성 경계: adapter 레이어 — 도메인 분포 타입·application 계약만. Spring/JPA/JDA·routing/GLM 미참조(결정론).
 */
object OnnxPolicyResponseDecoder {
    /** action head 인덱스 순서(ML datasets.ACTION_HEAD_CLASSES 미러). */
    private val ACTION_ORDER =
        listOf(
            SocialActionKind.IGNORE,
            SocialActionKind.WAIT,
            SocialActionKind.REACT,
            SocialActionKind.SPEAK,
            SocialActionKind.CANCEL_PENDING,
        )

    /** delay head 인덱스 순서(ML datasets.DELAY_BINS → DelayBucket 미러). */
    private val DELAY_ORDER =
        listOf(
            DelayBucket.IMMEDIATE,
            DelayBucket.SHORT,
            DelayBucket.MEDIUM,
            DelayBucket.LONG,
            DelayBucket.NEVER,
        )

    /** social-act head 인덱스 순서(ML datasets.SOCIAL_ACT_CLASSES 미러). */
    private val ACT_ORDER =
        listOf(
            SocialAct.ACKNOWLEDGE,
            SocialAct.AGREE,
            SocialAct.ASK,
            SocialAct.TEASE,
            SocialAct.SELF_DISCLOSE,
            SocialAct.UNKNOWN,
        )

    private const val MAX_FRAGMENT_LENGTH = 280

    /**
     * head 확률 배열을 [PolicyDecisionResponse] 로 복원한다. 각 배열은 해당 head softmax(합≈1) 한 행이다.
     * 부동소수 잔차로 합이 정확히 1 이 아닐 수 있어 계약 통과를 위해 [normalize] 로 재정규화한다.
     */
    fun decode(
        action: FloatArray,
        delay: FloatArray,
        burst: FloatArray,
        act: FloatArray,
        modelVersion: String,
    ): PolicyDecisionResponse {
        require(action.size == ACTION_ORDER.size) { "action head 폭 불일치: ${action.size}" }
        require(delay.size == DELAY_ORDER.size) { "delay head 폭 불일치: ${delay.size}" }
        require(act.size == ACT_ORDER.size) { "act head 폭 불일치: ${act.size}" }
        require(burst.size == BURST_WIDTH) { "burst head 폭 불일치: ${burst.size}" }

        val actionWeights = normalize(ACTION_ORDER.zip(action.toList()) { k, p -> k to p.toDouble() }.toMap())
        val delayWeights = normalize(DELAY_ORDER.zip(delay.toList()) { b, p -> b to p.toDouble() }.toMap())
        val actWeights = normalize(ACT_ORDER.zip(act.toList()) { a, p -> a to p.toDouble() }.toMap())

        return PolicyDecisionResponse(
            actionWeights = actionWeights,
            targetDistribution = ActionTargetDistribution.none(resolverVersion = modelVersion),
            delayDistribution = DelayDistribution(delayWeights),
            socialActWeights = actWeights,
            burstProfile = burstProfile(burst),
            uncertainty = normalizedEntropy(actionWeights.values),
            modelVersion = modelVersion,
        )
    }

    /** burst head(none/single/multi) → BurstProfile. none=reaction-only, single=1 조각, multi=2 조각. */
    private fun burstProfile(burst: FloatArray): BurstProfile {
        val none = burst[0].toDouble()
        val single = burst[1].toDouble()
        val multi = burst[2].toDouble()
        // 조각 수 분포는 발화 시(single/multi)만 의미 — 재정규화. 둘 다 0 이면 1 조각 확정(계약 통과).
        val speakMass = single + multi
        val fragmentWeights =
            if (speakMass <= 0.0) {
                mapOf(1 to 1.0)
            } else {
                normalizeInt(mapOf(1 to single, 2 to multi))
            }
        return BurstProfile(
            fragmentCountWeights = fragmentWeights,
            maxFragmentLength = MAX_FRAGMENT_LENGTH,
            gapLowerBound = java.time.Duration.ZERO,
            gapUpperBound = java.time.Duration.ofSeconds(2),
            reactionOnlyProbability = none.coerceIn(0.0, 1.0),
        )
    }

    /** 확률 맵을 합=1 로 재정규화(부동소수 잔차 흡수). 합이 0 이면 균등 분포. */
    private fun <K> normalize(weights: Map<K, Double>): Map<K, Double> {
        val clamped = weights.mapValues { it.value.coerceAtLeast(0.0) }
        val sum = clamped.values.sum()
        if (sum <= 0.0) {
            val uniform = 1.0 / clamped.size
            return clamped.mapValues { uniform }
        }
        return clamped.mapValues { it.value / sum }
    }

    /** Int 키 버전 재정규화(burst fragment count). */
    private fun normalizeInt(weights: Map<Int, Double>): Map<Int, Double> = normalize(weights)

    /** 확률분포의 정규화 Shannon 엔트로피 [0,1](0=확정, 1=균등). uncertainty 신호. */
    private fun normalizedEntropy(probs: Collection<Double>): Double {
        val n = probs.size
        if (n <= 1) return 0.0
        val entropy = probs.filter { it > 0.0 }.sumOf { -it * ln(it) }
        return (entropy / ln(n.toDouble())).coerceIn(0.0, 1.0)
    }

    private const val BURST_WIDTH = 3
}
