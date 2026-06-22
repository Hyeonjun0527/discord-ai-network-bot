package com.discordassistant.central.participation.domain.model.decision

import java.time.Duration
import kotlin.random.Random

/**
 * 발화 버스트 형태 프로파일(NEXA-P08-T005, 순수 도메인 값 객체·불변).
 *
 * SPEAK 가 결정됐을 때 NEXA 가 **어떤 형태로** 말할지의 **형태(shape)만** 정한다 — 몇 조각으로 나눠 보낼지,
 * 각 조각 최대 길이, 조각 사이 간격, 그리고 발화 대신 reaction 만 할 가능성. 사람처럼 "한 줄/여러 줄 쪼개기/
 * 그냥 리액션" 이 섞이도록.
 *
 * **acceptance(T005) — 정책이 실제 문구를 생성하지 않고 형태만 정한다**:
 * 이 객체에는 **어떤 텍스트 필드도 없다**(조각 수·길이 상한·간격·reaction-only 확률뿐). 실제 문구는 speech 가
 * 만든다(participation 불변식 2). [maxFragmentLength] 는 길이 *상한* 일 뿐 내용이 아니다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time·kotlin.random 만 쓴다.
 */
data class BurstProfile(
    /** 메시지 조각 수의 확률분포(키=조각 수 ≥1, 값=확률). 확률 합 = 1.0 — [init] 검증. */
    val fragmentCountWeights: Map<Int, Double>,
    /** 각 조각의 최대 글자 길이(형태 상한, 내용 아님). > 0. */
    val maxFragmentLength: Int,
    /** 조각 사이 최소 간격(타이핑 텀의 하한). 음수 금지, ≤ [gapUpperBound]. */
    val gapLowerBound: Duration,
    /** 조각 사이 최대 간격(타이핑 텀의 상한). 음수 금지, ≥ [gapLowerBound]. */
    val gapUpperBound: Duration,
    /** 발화 대신 reaction 만 할 가능성 [0,1]. 높으면 말 없이 이모지로 끝낼 수 있음(REACT 로 접힐 수 있음). */
    val reactionOnlyProbability: Double,
) {
    init {
        require(fragmentCountWeights.isNotEmpty()) { "fragmentCountWeights 는 비어 있을 수 없다" }
        fragmentCountWeights.forEach { (count, p) ->
            require(count >= 1) { "조각 수는 1 이상이어야 한다: $count" }
            require(p in 0.0..1.0) { "조각 수 확률은 [0,1] 범위여야 한다: $p" }
        }
        val total = fragmentCountWeights.values.sum()
        require(kotlin.math.abs(total - 1.0) <= EPSILON) {
            "조각 수 확률 합은 1.0 이어야 한다(허용오차 $EPSILON): 합=$total"
        }
        require(maxFragmentLength > 0) { "maxFragmentLength 는 양수여야 한다: $maxFragmentLength" }
        require(!gapLowerBound.isNegative) { "gapLowerBound 는 음수일 수 없다" }
        require(!gapUpperBound.isNegative) { "gapUpperBound 는 음수일 수 없다" }
        require(gapLowerBound <= gapUpperBound) { "gapLowerBound 는 gapUpperBound 보다 클 수 없다" }
        require(reactionOnlyProbability in 0.0..1.0) {
            "reactionOnlyProbability 는 [0,1] 범위여야 한다: $reactionOnlyProbability"
        }
    }

    /** 가장 그럴듯한 조각 수(동률이면 작은 값). */
    val mostLikelyFragmentCount: Int
        get() =
            fragmentCountWeights.entries
                .maxWithOrNull(
                    compareBy<Map.Entry<Int, Double>> { it.value }.thenByDescending { it.key },
                )?.key ?: 1

    /**
     * seed 로 구체 형태([SampledBurstShape])를 뽑는다(결정론 — 같은 seed=같은 결과): 조각 수, 조각 간 간격,
     * 그리고 reaction-only 여부. **텍스트는 만들지 않는다**(형태만).
     */
    fun sample(seed: Long): SampledBurstShape {
        val random = Random(seed)
        val reactionOnly = random.nextDouble() < reactionOnlyProbability
        val fragmentCount = pickFragmentCount(random.nextDouble())
        val gap = sampleGap(random)
        return SampledBurstShape(
            fragmentCount = fragmentCount,
            maxFragmentLength = maxFragmentLength,
            gapBetweenFragments = gap,
            reactionOnly = reactionOnly,
        )
    }

    private fun pickFragmentCount(roll: Double): Int {
        var cumulative = 0.0
        var last = mostLikelyFragmentCount
        for ((count, p) in fragmentCountWeights.entries.sortedBy { it.key }) {
            if (p > 0.0) last = count
            cumulative += p
            if (roll < cumulative) return count
        }
        return last
    }

    private fun sampleGap(random: Random): Duration {
        val lo = gapLowerBound.toMillis()
        val hi = gapUpperBound.toMillis()
        val picked = if (hi <= lo) lo else random.nextLong(lo, hi + 1)
        return Duration.ofMillis(picked)
    }

    companion object {
        /** 확률 합 검증의 부동소수 허용오차. */
        const val EPSILON: Double = 1e-9

        /** 한 줄·짧은 텀의 기본 프로파일(조각 1개, reaction-only 없음). */
        fun singleLine(maxFragmentLength: Int = 280): BurstProfile =
            BurstProfile(
                fragmentCountWeights = mapOf(1 to 1.0),
                maxFragmentLength = maxFragmentLength,
                gapLowerBound = Duration.ZERO,
                gapUpperBound = Duration.ZERO,
                reactionOnlyProbability = 0.0,
            )
    }
}

/**
 * BurstProfile 에서 샘플된 구체 형태(순수 도메인 값 객체). 텍스트 없음 — 형태(조각 수/길이 상한/간격/reaction-only)만.
 */
data class SampledBurstShape(
    val fragmentCount: Int,
    val maxFragmentLength: Int,
    val gapBetweenFragments: Duration,
    val reactionOnly: Boolean,
)
