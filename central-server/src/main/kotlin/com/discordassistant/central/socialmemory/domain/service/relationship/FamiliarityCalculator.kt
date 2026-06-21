package com.discordassistant.central.socialmemory.domain.service.relationship

import com.discordassistant.central.socialmemory.domain.model.relationship.MemberInteractionState
import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/**
 * familiarity(친밀도) 지표 계산기(NEXA-P06-T005, 순수 함수·무상태).
 *
 * **서로 교환한 burst 수와 최근성**만으로 bounded familiarity [0,1] 을 만든다(observable-state-policy 허용:
 * 상호작용 빈도·최근성의 집계). 친밀도 "감정" 이 아니라 관찰된 교환량의 정규화 값이며, 관계 *감정* 을 단정하지 않는다.
 *
 * **acceptance(T005) — 단순 서버 체류 기간만으로 친밀함을 높이지 않는다**:
 * 입력은 [MemberInteractionState.totalExchangedBursts](실제 교환)와 [MemberInteractionState.lastInteractionAt]
 * (최근성)뿐이다. 가입/체류 시간은 입력이 아니다 — 교환이 0 이면 familiarity 는 0 이다(체류만으로 오르지 않음).
 *
 * 시간 유효성: 최근성 감쇠([halfLife])로 오래된 관계는 약화된다(영구 낙인 금지, 불변식 3).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time·kotlin.math 만 쓴다.
 */
object FamiliarityCalculator {
    /**
     * bounded familiarity [0,1] 을 계산한다.
     *
     * volume = 교환 burst 의 포화 곡선(1 - exp(-exchanged / [saturationScale])) — 교환이 많을수록 1 에 수렴.
     * recency = 마지막 상호작용 이후 경과의 지수 감쇠(half-life [halfLife]).
     * familiarity = volume * recency — 교환이 없거나 너무 오래되면 0 으로 떨어진다.
     *
     * @param state 관찰된 상호작용 통계.
     * @param now 현재 시각(주입 — 도메인은 Clock 을 갖지 않는다).
     * @param saturationScale 교환량 포화 척도(>0). 클수록 천천히 1 에 수렴.
     * @param halfLife 최근성 반감기(>0). 이 기간이 지나면 recency 가 0.5 가 된다.
     */
    fun familiarity(
        state: MemberInteractionState,
        now: Instant,
        saturationScale: Double = DEFAULT_SATURATION_SCALE,
        halfLife: Duration = DEFAULT_HALF_LIFE,
    ): Double {
        require(saturationScale > 0.0) { "saturationScale 은 양수여야 한다" }
        require(!halfLife.isZero && !halfLife.isNegative) { "halfLife 는 양수여야 한다" }

        val exchanged = state.totalExchangedBursts
        if (exchanged == 0) return 0.0
        val lastAt = state.lastInteractionAt ?: return 0.0

        val volume = 1.0 - exp(-exchanged.toDouble() / saturationScale)
        val elapsedMillis =
            Duration
                .between(lastAt, now)
                .toMillis()
                .coerceAtLeast(0)
                .toDouble()
        val recency = exp(-LN2 * elapsedMillis / halfLife.toMillis().toDouble())
        return (volume * recency).coerceIn(0.0, 1.0)
    }

    private const val LN2 = 0.6931471805599453
    private const val DEFAULT_SATURATION_SCALE = 20.0
    private val DEFAULT_HALF_LIFE: Duration = Duration.ofDays(14)
}
