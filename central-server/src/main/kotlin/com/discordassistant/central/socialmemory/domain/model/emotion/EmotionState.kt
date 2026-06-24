package com.discordassistant.central.socialmemory.domain.model.emotion

import java.time.Instant

/**
 * 니아의 현재 감정 상태 — reaction/mood 두 축(core `emotion.py`, SSOT §11). **전역(맥락별), 사람별 아님.**
 *
 * [contextScope](서버/채널 키)로 식별한다 — "니아의 기분"은 특정 사람이 아니라 *지금 대화 맥락*의 것이다
 * (SSOT §11: reaction/mood = 니아 자신 상태, affinity[user] = 별개 사람별 축). 서버 격리(I7)는 contextScope
 * 키로 보존한다(다른 맥락 기분 유입 없음). 사실/관계/감정 분리(I6) — 본 모델은 "감정"만 담는다.
 *
 * 외부가 reaction/mood 숫자를 직접 쓰는 공개 경로는 없다 — 변화는 오직 [EmotionEngine.applyEvent](등급→수학)
 * 뿐이다(I2 조작 방지). 불변 데이터 클래스이므로 갱신은 *새* 인스턴스를 만든다.
 *
 * @param reaction 순간 반응. μ=0 = 평소 니아(무색 중립 아님). 범위 ±0.20.
 * @param mood 느린 기분. 범위 ±0.10, 한 사건 δ 매우 작아(0.006) 렌더 임계(0.025) 미진입.
 * @param lastUpdatedAt 마지막 갱신 시각(감쇠 Δt 계산용). null = 미초기화(사건 없음).
 */
data class EmotionState(
    val contextScope: String,
    val reaction: Double = 0.0,
    val mood: Double = 0.0,
    val lastUpdatedAt: Instant? = null,
)
