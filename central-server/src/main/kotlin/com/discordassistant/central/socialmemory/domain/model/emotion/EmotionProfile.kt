package com.discordassistant.central.socialmemory.domain.model.emotion

import java.time.Duration

/**
 * 한 감정 상태(reaction/mood)의 *독립된* 보정 프로필 — core `nia_engine/emotion.py`(D1, 게이트 G4) 이식.
 *
 * 축마다 기준점 μ([mu], "평소의 친근·장난·솔직 니아" — 무색 중립 아님)·시정수 H([halfLife], 반감기)·
 * 전체 범위 [[xMin], [xMax]]·한 사건 최대 변화 δ([deltaMax], I4 단일 사건 상한)·부정 비대칭([negativityBias],
 * SSOT §12.3)을 따로 갖는다. 이 독립성이 reaction/mood 의 *다른 시정수*를 만든다(감정은 관계보다 빠르다).
 *
 * 순수 데이터(불변). Spring/JPA 미참조 — core AxisProfile 과 1:1.
 */
data class EmotionProfile(
    val name: String,
    val mu: Double,
    val halfLife: Duration,
    val xMin: Double,
    val xMax: Double,
    val deltaMax: Double,
    val negativityBias: Double,
) {
    /** 전체 범위 클립 — I4 누적/범위 제한. */
    fun clamp(x: Double): Double = x.coerceIn(xMin, xMax)
}

/**
 * 사회 사건 강도 등급 — GLM 은 등급만 판정하고(I2), 숫자([impact])는 코드가 정한다.
 * 값 정본: SSOT §12 캘리브레이션(core `relationship.GRADE_IMPACT`).
 */
enum class EmotionEventGrade(
    val impact: Double,
) {
    MICRO(0.010),
    MILD(0.025),
    CLEAR(0.055),
    STRONG(0.100),
}

/** 사건 방향 — +1(긍정)·−1(부정)만(랜덤·임의 가중치 금지, I1). */
enum class EmotionEventSign(
    val value: Double,
) {
    POSITIVE(1.0),
    NEGATIVE(-1.0),
}
