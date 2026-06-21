package com.discordassistant.central.participation.domain.model.state

import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/**
 * social energy(사회적 에너지) 잠재 상태(NEXA-P06-T012, 순수 도메인 값 객체·불변).
 *
 * 정책 **다양성**을 위한 bounded latent state [0,1] 다 — 같은 입력에도 응답 스타일이 단조롭지 않게 변주를 준다.
 *
 * **윤리·acceptance(T012)**:
 * - "**실제 감정이라고 저장·노출하지 않는다**": 이 값은 정책용 latent scalar 일 뿐 기분/감정이 아니다(observable-state-policy
 *   금지: 감정 상태 추론). 사용자에게 감정으로 표현하지 않는다([isEmotion] 항상 false 가드).
 * - "**seed 로 replay 가능하다**": 상태는 ([level], [seed], [baseline], [updatedAt]) 로 완전 결정되며, 모든 갱신이
 *   순수 함수다(주입된 [Instant] 기반 감쇠). 같은 seed·입력 순서면 같은 궤적을 재현한다(결정론).
 *
 * 시간 유효성: baseline 으로의 지수 회귀(mean-reversion)로 시간이 지나면 [baseline] 에 수렴한다(영구 상태 금지).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time·kotlin.math 만 쓴다.
 */
data class SocialEnergy(
    /** 현재 잠재 에너지 [0,1]. 정책 변주 입력일 뿐 감정 아님. */
    val level: Double,
    /** replay 결정성을 위한 seed(같은 seed·입력이면 같은 궤적). */
    val seed: Long,
    /** 시간이 지나면 회귀하는 기준선 [0,1]. */
    val baseline: Double = DEFAULT_BASELINE,
    /** 마지막 갱신 시각. null 이면 아직 시간 감쇠가 적용된 적 없음. */
    val updatedAt: Instant? = null,
) {
    init {
        require(level in 0.0..1.0) { "level 은 [0,1] 범위여야 한다" }
        require(baseline in 0.0..1.0) { "baseline 은 [0,1] 범위여야 한다" }
        require(!isEmotion) { "SocialEnergy 는 실제 감정이 아니라 정책 latent state 다(acceptance T012)" }
    }

    /**
     * 실제 감정 여부 — **항상 false**. 정책 latent scalar 일 뿐 감정으로 저장·노출하지 않는다는 불변식의 가드다
     * (acceptance T012, observable-state-policy 금지 추론 부재).
     */
    val isEmotion: Boolean
        get() = false

    /**
     * [now] 까지의 시간 경과로 [baseline] 을 향해 지수 회귀시킨 새 상태(불변). half-life [halfLife] 마다 baseline
     * 과의 거리가 절반이 된다. [updatedAt] 이 null 이면 시각만 채우고 level 은 그대로(첫 기준점).
     */
    fun decayed(
        now: Instant,
        halfLife: Duration = DEFAULT_HALF_LIFE,
    ): SocialEnergy {
        require(!halfLife.isZero && !halfLife.isNegative) { "halfLife 는 양수여야 한다" }
        val last = updatedAt ?: return copy(updatedAt = now)
        val elapsedMillis =
            Duration
                .between(last, now)
                .toMillis()
                .coerceAtLeast(0)
                .toDouble()
        val retain = exp(-LN2 * elapsedMillis / halfLife.toMillis().toDouble())
        val reverted = baseline + (level - baseline) * retain
        return copy(level = reverted.coerceIn(0.0, 1.0), updatedAt = now)
    }

    /**
     * 외부 자극 [delta] 를 더한 새 상태([0,1] clamp). [updatedAt] 을 [at] 로 갱신한다. 결정론적(순수 산술)이라
     * replay 가능하다.
     */
    fun nudged(
        delta: Double,
        at: Instant,
    ): SocialEnergy = copy(level = (level + delta).coerceIn(0.0, 1.0), updatedAt = at)

    companion object {
        private const val LN2 = 0.6931471805599453
        private const val DEFAULT_BASELINE = 0.5
        private val DEFAULT_HALF_LIFE: Duration = Duration.ofHours(6)

        /** [seed] 로 baseline 에서 시작하는 초기 상태(결정론적 replay 시작점). */
        fun seeded(
            seed: Long,
            baseline: Double = DEFAULT_BASELINE,
        ): SocialEnergy = SocialEnergy(level = baseline, seed = seed, baseline = baseline)
    }
}
