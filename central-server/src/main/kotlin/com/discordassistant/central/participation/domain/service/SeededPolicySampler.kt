package com.discordassistant.central.participation.domain.service

import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionDelay
import com.discordassistant.central.participation.domain.model.decision.ActionDistribution
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.SampledBurstShape
import com.discordassistant.central.participation.domain.model.decision.TargetRef
import kotlin.random.Random

/**
 * 결정론적 seed 기반 정책 샘플러(NEXA-P08-T019, 순수 도메인 서비스·무상태).
 *
 * 결정 엔진이 돌려준 확률분포([ActionDistribution])를 **seed 하나로** 단일 구체 결과([SampledPolicyOutcome])로
 * 접는다 — action kind, target, delay, socialAct, burst shape 를 각각 샘플링한다. participation 의 "확률분포 →
 * 단 하나의 행동" 접기 단계다(participation-context.md 불변식 1: 한 평가는 정확히 하나의 행동).
 *
 * **acceptance(T019) — 같은 입력과 seed 로 같은 결과가 나오고 분포 밖 값이 나오지 않는다**:
 * - **결정론**: 같은 [distribution]·같은 [seed] 면 항상 같은 [SampledPolicyOutcome]. 차원별로 base seed 에서 파생한
 *   **고정 sub-seed**(서로 다른 상수 혼합)로 독립 [Random] 을 만들어, 차원 추가/순서와 무관하게 재현된다.
 * - **분포 밖 값 금지**: action kind 는 [ActionDistribution.actionWeights] 키 중에서만, socialAct 는
 *   socialActWeights 키 중에서만, target 은 분포의 후보(또는 none) 중에서만 뽑는다 — 분포에 없는 값은 절대 나오지
 *   않는다. delay/burst 는 각 분포의 자체 sample 로 위임한다(그들도 분포 안에서만 뽑음).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 kotlin.random 만 쓴다.
 */
object SeededPolicySampler {
    // 차원별 sub-seed 혼합 상수(서로 다른 홀수 — 차원 간 상관 제거, 결정론 유지). 부호 있는 Long 리터럴(상수).
    private const val ACTION_SALT = -0x61c8864680b583ebL
    private const val TARGET_SALT = -0x3d4d51c2d82b14b1L
    private const val SOCIAL_ACT_SALT = 0x165667B19E3779F9L
    private const val DELAY_SALT = 0x27D4EB2F165667C5L
    private const val BURST_SALT = -0x6b2fb644eccee15L

    /** [distribution] 을 [seed] 로 단일 구체 결과로 접는다(결정론·분포 내). */
    fun sample(
        distribution: ActionDistribution,
        seed: Long,
    ): SampledPolicyOutcome {
        val action = pickAction(distribution.actionWeights, subSeed(seed, ACTION_SALT))
        val target = pickTarget(distribution.targetDistribution, subSeed(seed, TARGET_SALT))
        val delay = distribution.delayDistribution.sample(subSeed(seed, DELAY_SALT))
        val socialAct = pickSocialAct(distribution.socialActWeights, subSeed(seed, SOCIAL_ACT_SALT))
        val burstShape = distribution.burstProfile.sample(subSeed(seed, BURST_SALT))
        return SampledPolicyOutcome(
            action = action,
            target = target,
            delay = delay,
            socialAct = socialAct,
            burstShape = burstShape,
        )
    }

    /** base seed 와 차원 salt 를 혼합한 결정론적 sub-seed(차원 독립·재현). */
    private fun subSeed(
        seed: Long,
        salt: Long,
    ): Long = seed * 0x100000001B3L xor salt

    /** action kind 를 가중치 키 중에서만 뽑는다(분포 밖 금지). 선언 순서로 누적해 안정 결정. */
    private fun pickAction(
        weights: Map<SocialActionKind, Double>,
        seed: Long,
    ): SocialActionKind {
        val ordered = SocialActionKind.entries.filter { (weights[it] ?: 0.0) > 0.0 }
        // 가중치가 전부 0/비면 IGNORE(안전 기본 — 분포 밖 아님).
        if (ordered.isEmpty()) return SocialActionKind.IGNORE
        return pickWeighted(ordered, { weights.getValue(it) }, Random(seed))
    }

    /** socialAct 를 가중치 키 중에서만 뽑는다. 비면 null(SPEAK 종류 미정 — UNKNOWN 으로 강제하지 않음). */
    private fun pickSocialAct(
        weights: Map<SocialAct, Double>,
        seed: Long,
    ): SocialAct? {
        val ordered = SocialAct.entries.filter { (weights[it] ?: 0.0) > 0.0 }
        if (ordered.isEmpty()) return null
        return pickWeighted(ordered, { weights.getValue(it) }, Random(seed))
    }

    /** target 을 분포의 후보(또는 none) 중에서만 뽑는다. none 이면 null(채널 전체/혼잣말). */
    private fun pickTarget(
        distribution: ActionTargetDistribution,
        seed: Long,
    ): TargetRef? {
        val random = Random(seed)
        var cumulative = 0.0
        val roll = random.nextDouble()
        for (candidate in distribution.candidates) {
            cumulative += candidate.probability
            if (roll < cumulative) return candidate.target
        }
        // 잔여(none 구간 포함 또는 부동소수 잔차)는 none.
        return null
    }

    /** 누적 가중치로 [items] 중 하나를 뽑는다(roll [0,1)). 잔차는 마지막 후보로 흡수(분포 밖 금지). */
    private fun <T> pickWeighted(
        items: List<T>,
        weightOf: (T) -> Double,
        random: Random,
    ): T {
        val total = items.sumOf(weightOf)
        val roll = random.nextDouble() * total
        var cumulative = 0.0
        for (item in items) {
            cumulative += weightOf(item)
            if (roll < cumulative) return item
        }
        return items.last()
    }
}

/**
 * 분포에서 seed 로 샘플된 단일 구체 정책 결과(NEXA-P08-T019, 순수 도메인 값 객체). 분포를 접은 "이번 평가의 선택"
 * 이다 — action kind·대상·delay·socialAct·burst 형태. 실제 [com.discordassistant.central.participation.domain.model.action.SocialAction]
 * 구성(correlationId·pendingActionId 등 context ref 필요)은 호출자(application)가 이 값으로 한다.
 *
 * 텍스트 없음(불변식 2) — socialAct 는 코드, burstShape 는 형태(조각 수·길이 상한·간격)만.
 */
data class SampledPolicyOutcome(
    /** 이번 평가에서 고른 행동 종류(분포 키 중 하나, 빈 분포면 IGNORE). */
    val action: SocialActionKind,
    /** 행동 대상(분포 후보 중 하나, none 이면 null). */
    val target: TargetRef?,
    /** 발사 지연(분포에서 샘플, NEVER 가능). */
    val delay: ActionDelay,
    /** SPEAK 시 발화 종류(분포 키 중 하나, 미정이면 null). */
    val socialAct: SocialAct?,
    /** SPEAK 시 발화 형태(텍스트 없음 — 조각 수·길이 상한·간격·reaction-only). */
    val burstShape: SampledBurstShape,
)
