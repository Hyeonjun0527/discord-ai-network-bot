package com.discordassistant.central.participation.domain.model.decision

import java.time.Duration
import kotlin.random.Random

/**
 * 행동 지연(delay) 구간 분포(NEXA-P08-T004, 순수 도메인 값 객체·불변).
 *
 * NEXA 가 행동(REACT/SPEAK)을 **언제** 할지를 단일 시간이 아니라 **구간 확률분포** 로 표현한다 — 사람처럼
 * "바로/조금 뜸들이고/한참 뒤" 가 섞이도록. seed 로 결정론적 샘플([sample])을 만든다(재현 가능).
 *
 * **acceptance(T004) — 초기 계약 구간**: [DelayBucket] 은 즉시·3~10초·10~30초·30~120초·never 다.
 *
 * **acceptance(T004) — never 와 IGNORE 의 차이(문서화)**:
 * | 개념 | 의미 | 결과 |
 * | --- | --- | --- |
 * | [DelayBucket.NEVER] | 행동을 골랐으나(예: SPEAK/REACT 의도) **이번 창에서는 발사하지 않음**(타이밍상 보류) | 행동 객체는 존재하되 예약이 발사되지 않음 — 다음 평가에서 재고될 수 있음 |
 * | SocialAction.Ignore | 행동을 **고르지 않음**(이 장면에 할 일 없음) | 행동 자체가 없음 — 재평가 트리거가 없으면 끝 |
 *
 * 즉 NEVER 는 "할 수도 있었지만 지금 타이밍이 아니다"(delay 차원), IGNORE 는 "할 게 없다"(action 차원)다.
 * 두 개념은 서로 다른 축이라 혼동하면 안 된다 — NEVER 를 IGNORE 로 접으면 재평가 기회를 잃는다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time·kotlin.random 만 쓴다.
 */
data class DelayDistribution(
    /** 구간별 확률(빠진 구간은 0). 확률 합 = 1.0(허용오차 내) — [init] 검증. */
    val weights: Map<DelayBucket, Double>,
) {
    init {
        weights.forEach { (bucket, p) ->
            require(p in 0.0..1.0) { "$bucket 확률은 [0,1] 범위여야 한다: $p" }
        }
        val total = weights.values.sum()
        require(kotlin.math.abs(total - 1.0) <= EPSILON) {
            "구간 확률 합은 1.0 이어야 한다(허용오차 $EPSILON): 합=$total"
        }
    }

    /** 가장 확률이 높은 구간(동률이면 enum 선언 순서상 먼저). */
    val mostLikelyBucket: DelayBucket
        get() = DelayBucket.entries.maxByOrNull { weights[it] ?: 0.0 } ?: DelayBucket.IMMEDIATE

    /**
     * seed 로 구간을 뽑고 그 구간 안에서 균등하게 구체 지연([ActionDelay])을 만든다(결정론 — 같은 seed=같은 결과).
     * [DelayBucket.NEVER] 가 뽑히면 [ActionDelay.NEVER](발사 안 함)를 돌려준다.
     */
    fun sample(seed: Long): ActionDelay {
        val random = Random(seed)
        val bucket = pickBucket(random.nextDouble())
        return bucket.sampleDelay(random)
    }

    /** 누적 확률로 구간을 고른다(roll [0,1)). 부동소수 잔차는 마지막 비-0 구간으로 흡수. */
    private fun pickBucket(roll: Double): DelayBucket {
        var cumulative = 0.0
        var lastNonZero = DelayBucket.IMMEDIATE
        for (bucket in DelayBucket.entries) {
            val p = weights[bucket] ?: 0.0
            if (p > 0.0) lastNonZero = bucket
            cumulative += p
            if (roll < cumulative) return bucket
        }
        return lastNonZero
    }

    companion object {
        /** 확률 합 검증의 부동소수 허용오차. */
        const val EPSILON: Double = 1e-9

        /** 즉시 발사가 확실한 분포(IMMEDIATE = 1.0). 멘션 등 즉응 상황의 기본형. */
        val IMMEDIATE: DelayDistribution = DelayDistribution(mapOf(DelayBucket.IMMEDIATE to 1.0))

        /** 절대 발사하지 않는 분포(NEVER = 1.0). "지금 타이밍 아님"(IGNORE 와 다름 — 위 문서 참조). */
        val NEVER: DelayDistribution = DelayDistribution(mapOf(DelayBucket.NEVER to 1.0))
    }
}

/**
 * delay 구간(NEXA-P08-T004 초기 계약). 각 구간은 닫힌 시간 범위를 가지며 [NEVER] 만 범위가 없다(발사 안 함).
 */
enum class DelayBucket(
    val lowerBound: Duration?,
    val upperBound: Duration?,
) {
    /** 즉시(0초). */
    IMMEDIATE(Duration.ZERO, Duration.ZERO),

    /** 3~10초. */
    SHORT(Duration.ofSeconds(3), Duration.ofSeconds(10)),

    /** 10~30초. */
    MEDIUM(Duration.ofSeconds(10), Duration.ofSeconds(30)),

    /** 30~120초. */
    LONG(Duration.ofSeconds(30), Duration.ofSeconds(120)),

    /** never — 이번 창에서 발사하지 않음(범위 없음). IGNORE 와 구분(클래스 KDoc 표 참조). */
    NEVER(null, null),
    ;

    /** 구간 안에서 균등하게 구체 지연을 뽑는다. NEVER 면 [ActionDelay.NEVER]. */
    fun sampleDelay(random: Random): ActionDelay {
        val lo = lowerBound ?: return ActionDelay.NEVER
        val hi = upperBound ?: return ActionDelay.NEVER
        val loMillis = lo.toMillis()
        val hiMillis = hi.toMillis()
        val picked = if (hiMillis <= loMillis) loMillis else random.nextLong(loMillis, hiMillis + 1)
        return ActionDelay.fire(Duration.ofMillis(picked))
    }
}

/**
 * 샘플된 구체 행동 지연(NEXA-P08-T004, 순수 도메인 값 객체·불변). SocialAction(Wait/React/Speak)이 운반하는
 * "실제로 이만큼 뒤에 발사" 값이다. [NEVER] 는 발사하지 않음을 뜻한다(IGNORE 와 다른 축 — DelayDistribution 표).
 */
data class ActionDelay private constructor(
    /** 발사까지의 지연. [fires] = false 면 의미 없음(0). */
    val duration: Duration,
    /** 이번에 실제로 발사하는가 — false 면 never(지금 타이밍 아님). */
    val fires: Boolean,
) {
    companion object {
        /** 즉시 발사(0초). */
        val IMMEDIATE: ActionDelay = ActionDelay(Duration.ZERO, fires = true)

        /** 발사하지 않음(never). 행동을 골랐으나 이번 창에서는 보류 — IGNORE 와 다르다. */
        val NEVER: ActionDelay = ActionDelay(Duration.ZERO, fires = false)

        /** [duration] 뒤에 발사하는 지연. 음수 금지. */
        fun fire(duration: Duration): ActionDelay {
            require(!duration.isNegative) { "지연은 음수일 수 없다: $duration" }
            return ActionDelay(duration, fires = true)
        }
    }
}
