package com.discordassistant.central.socialmemory.domain.service.decay

import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/**
 * 사회 상태 시간 감쇠 함수(NEXA-P06-T017, 순수 함수·무상태, risk medium).
 *
 * familiarity/topic/outcome 같은 bounded [0,1] 지표가 **마지막 관찰 이후 경과 시간**에 따라 반감기([halfLife])로
 * 감쇠하되, 지표별 **최소값([floor])** 아래로는 떨어지지 않게 한다(타입 안전 [HalfLifeDecay]). 시간이 지나면
 * 영구 낙인이 되지 않게 약화되지만(observable-state-policy 불변식 3·체크리스트 #6), floor 로 표본이 있었다는
 * 관찰 사실을 0 으로 완전히 지우지 않을 수 있다(지표 정책에 위임).
 *
 * **acceptance(T017) — 시스템 시각 직접 접근 없이 시간 이동 테스트가 가능하다**:
 * 이 함수는 [Instant.now]/[System] 을 호출하지 않는다 — [lastObservedAt] 와 [now] 를 **인자로 받는다**(Clock 주입은
 * 호출자 책임). 테스트는 같은 [HalfLifeDecay] 로 [now] 만 옮겨 감쇠 곡선을 결정론적으로 검증한다(시간 이동).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time·kotlin.math 만 쓴다.
 */
object SocialStateDecay {
    /**
     * [baseValue] 를 [lastObservedAt]→[now] 경과에 [policy] 반감기로 감쇠시킨다. 결과는 [HalfLifeDecay.floor]
     * 이상, baseValue 이하로 클램프된다([0,1] bounded). 경과가 0 이면 baseValue 그대로, 무한대로 가면 floor 로 수렴.
     *
     * @param baseValue 감쇠 전 지표값 [0,1].
     * @param lastObservedAt 마지막 관찰 시각(이 시점엔 baseValue).
     * @param now 현재 시각(주입 — 시스템 시각 직접 접근 금지, acceptance T017).
     * @param policy 지표별 반감기·최소값 정책.
     */
    fun decayed(
        baseValue: Double,
        lastObservedAt: Instant,
        now: Instant,
        policy: HalfLifeDecay,
    ): Double {
        require(baseValue in 0.0..1.0) { "baseValue 는 [0,1] 범위여야 한다: $baseValue" }
        val elapsedMillis =
            Duration
                .between(lastObservedAt, now)
                .toMillis()
                .coerceAtLeast(0)
                .toDouble()
        val factor = exp(-LN2 * elapsedMillis / policy.halfLife.toMillis().toDouble())
        val decayed = baseValue * factor
        return decayed.coerceIn(policy.floor, baseValue)
    }

    private const val LN2 = 0.6931471805599453
}

/**
 * 지표별 반감기·최소값 정책(NEXA-P06-T017, 순수 value type·타입 안전).
 *
 * 반감기([halfLife])는 양수여야 하고, 최소값([floor])은 [0,1) 이어야 한다 — floor 가 1 이면 감쇠가 무의미하다.
 * 타입이 불변식을 강제해 잘못된 정책(0 또는 음수 반감기, 범위 밖 floor)을 생성 시점에 막는다.
 */
data class HalfLifeDecay(
    /** 반감기 — 이 기간이 지나면 감쇠 factor 가 0.5 가 된다. 양수. */
    val halfLife: Duration,
    /** 감쇠가 떨어질 수 있는 최소값 [0,1). 0 이면 완전 망각 허용. */
    val floor: Double = 0.0,
) {
    init {
        require(!halfLife.isZero && !halfLife.isNegative) { "halfLife 는 양수여야 한다(영구 낙인 방지 감쇠)" }
        require(floor >= 0.0 && floor < 1.0) { "floor 는 [0,1) 범위여야 한다: $floor" }
    }

    companion object {
        /** familiarity 기본 정책(반감기 14일, 완전 망각 허용). */
        val FAMILIARITY: HalfLifeDecay = HalfLifeDecay(halfLife = Duration.ofDays(14))

        /** topic affinity 기본 정책(반감기 21일, 완전 망각 허용). */
        val TOPIC: HalfLifeDecay = HalfLifeDecay(halfLife = Duration.ofDays(21))

        /** outcome 신호 기본 정책(반감기 7일, 완전 망각 허용 — 결과 반응은 더 빨리 잊는다). */
        val OUTCOME: HalfLifeDecay = HalfLifeDecay(halfLife = Duration.ofDays(7))
    }
}
