package com.discordassistant.central.socialmemory.domain.model.niarelationship

import java.time.Duration

/**
 * 니아↔사람 관계의 4축 — core `nia_engine/relationship.py`(B4) 이식. 축마다 *독립*이라 "축이 안 겹친다".
 * (NEXA 기존 `RelationshipOnlineUpdate`(EMA)와는 다른 모델 — core식 등급→Δ 보정표·μ회귀를 별도 이식.)
 */
enum class RelationshipAxis {
    /** 익숙함: 반복 상호작용·공유 사건. [0, 1], 사실상 누적. */
    FAMILIARITY,

    /** 호감: 지속 배려·도움·모욕. [-1, 1]. */
    AFFINITY,

    /** 신뢰: 정확성·약속 이행·정직한 정정·배신. [-1, 1]. 확인 가능한 신뢰 사건만 움직인다. */
    TRUST,

    /** 편안함: 상호 장난·경계 존중·안전한 대화. [0, 1]. */
    COMFORT,
}

/** 강도 등급 기본 충격량 — SSOT §12.2. GLM 은 등급만(I2), 숫자는 이 값. Emotion GRADE 와 동일 서열. */
enum class RelationshipGrade(
    val impact: Double,
) {
    MICRO(0.010),
    MILD(0.025),
    CLEAR(0.055),
    STRONG(0.100),
}

/**
 * 한 관계 축의 *독립된* 보정 프로필 — μ(평소)·시정수 H(반감기)·범위·δ_max(한 사건 상한 I4).
 * 이 독립성이 "축이 안 겹친다"를 수학적으로 만든다.
 */
data class AxisProfile(
    val axis: RelationshipAxis,
    val mu: Double,
    val halfLife: Duration,
    val xMin: Double,
    val xMax: Double,
    val deltaMax: Double,
) {
    /** 전체 범위 클립 — I4 누적/범위 제한. */
    fun clamp(x: Double): Double = x.coerceIn(xMin, xMax)
}
