package com.discordassistant.central.socialmemory.domain.service.relationship

import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/**
 * topic affinity(주제 관심도) 지표(NEXA-P06-T009, 순수 도메인 값 객체·무상태, risk high).
 *
 * **명시적 주제 tag 와 상호작용 빈도**로 guild-scoped 관심도 [0,1] 을 만든다(observable-state-policy 허용:
 * "특정 주제 대화에 참여한 빈도" 관찰).
 *
 * **윤리 가드(acceptance T009)**:
 * - "**민감 주제 자동 추론을 금지**": [tag] 는 호출자가 명시적으로 부여한 라벨([ExplicitTopicTag])이며, 이 도메인은
 *   원문에서 주제를 추론하지 않는다. observable-state-policy 금지 목록(정치·종교·성적지향·건강 등)을 자동 분류·점수화
 *   하지 않는다 — 그런 tag 는 호출자가 만들지 않는 것이 계약이다(이 모델은 임의 추론 분류기를 갖지 않는다).
 * - "**원문 장기 저장을 금지**": 이 모델은 원문을 담지 않는다 — tag 라벨([String])·참여 카운트·시각만이다
 *   ([rawContentStored] 항상 false 가드). data-categories.md: 원문/파생 텍스트 미저장.
 *
 * 시간 유효성: 마지막 참여 이후 지수 감쇠로 오래된 관심은 약화된다(영구 낙인 금지).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time·kotlin.math 만 쓴다.
 */
data class TopicAffinity(
    /** 호출자가 명시적으로 부여한 주제 tag(원문 아님 — 라벨). */
    val tag: ExplicitTopicTag,
    /** 이 주제 대화에 참여한 횟수(관찰 빈도). */
    val participationCount: Int,
    /** 가장 최근에 이 주제로 참여한 시각. null 이면 참여 없음. */
    val lastEngagedAt: Instant?,
) {
    init {
        require(participationCount >= 0) { "participationCount 는 음수일 수 없다" }
        require(!rawContentStored) { "TopicAffinity 는 원문을 장기 저장하지 않는다(acceptance T009)" }
    }

    /**
     * 원문 저장 여부 — **항상 false**. 라벨·카운트·시각만 담는다는 불변식의 가드다(acceptance T009,
     * data-categories.md 원문 미저장).
     */
    val rawContentStored: Boolean
        get() = false

    /**
     * 시간 감쇠가 적용된 bounded 관심도 [0,1] 을 계산한다.
     *
     * volume = 1 - exp(-participationCount / [saturationScale]) (참여가 많을수록 1 에 수렴).
     * recency = 마지막 참여 이후 지수 감쇠(half-life [halfLife]).
     * affinity = volume * recency.
     *
     * @param now 현재 시각(주입 — 도메인은 Clock 미보유).
     */
    fun affinity(
        now: Instant,
        saturationScale: Double = DEFAULT_SATURATION_SCALE,
        halfLife: Duration = DEFAULT_HALF_LIFE,
    ): Double {
        require(saturationScale > 0.0) { "saturationScale 은 양수여야 한다" }
        require(!halfLife.isZero && !halfLife.isNegative) { "halfLife 는 양수여야 한다" }
        if (participationCount == 0) return 0.0
        val lastAt = lastEngagedAt ?: return 0.0

        val volume = 1.0 - exp(-participationCount.toDouble() / saturationScale)
        val elapsedMillis =
            Duration
                .between(lastAt, now)
                .toMillis()
                .coerceAtLeast(0)
                .toDouble()
        val recency = exp(-LN2 * elapsedMillis / halfLife.toMillis().toDouble())
        return (volume * recency).coerceIn(0.0, 1.0)
    }

    /** 이 주제로 [at] 에 한 번 더 참여했음을 반영한 새 상태. */
    fun engage(at: Instant): TopicAffinity =
        copy(
            participationCount = participationCount + 1,
            lastEngagedAt = if (lastEngagedAt == null || at.isAfter(lastEngagedAt)) at else lastEngagedAt,
        )

    companion object {
        private const val LN2 = 0.6931471805599453
        private const val DEFAULT_SATURATION_SCALE = 10.0
        private val DEFAULT_HALF_LIFE: Duration = Duration.ofDays(21)

        /** 명시적으로 부여된 [tag] 의 빈 관심도(참여 0). */
        fun empty(tag: ExplicitTopicTag): TopicAffinity = TopicAffinity(tag = tag, participationCount = 0, lastEngagedAt = null)
    }
}

/**
 * 명시적 주제 tag(NEXA-P06-T009, 순수 value type). 호출자가 **명시적으로** 부여한 라벨이지 원문에서 추론한 것이
 * 아니다. 민감 주제(정치·종교·성적지향·건강 등) 자동 분류를 금지하므로 그런 tag 를 만드는 책임은 정책상 호출자에게
 * 없다(observable-state-policy 금지 목록).
 */
@JvmInline
value class ExplicitTopicTag(
    val label: String,
) {
    init {
        require(label.isNotBlank()) { "주제 tag label 은 비어 있을 수 없다" }
    }
}
