package com.discordassistant.central.speech.domain.model

/**
 * speech 도메인이 보는 발화 버스트 형태(NEXA-P14-T004/T010, 순수 도메인 값 객체·불변).
 *
 * participation 의 burstProfile 이 정한 **형태(shape)만** speech 어휘로 표현한다 — 몇 조각으로 나눠 말할지, 각
 * 조각 최대 길이, 발화 대신 reaction 만 할지. **텍스트 필드가 없다**(형태만). 실제 문구는 GLM 이 만든다.
 *
 * acceptance(T010) — 정책이 1개 버블을 고른 경우 모델이 4개를 강제하지 못한다: [fragmentCount] 는 컴파일러가
 * 프롬프트로 강제할 **확정 형태** 다(범위가 아님). speech 는 이 값을 늘리지 않는다.
 */
data class SpeechBurstShape(
    /** 메시지 조각 수(≥1). participation 이 확정한 형태 — speech 가 늘리지 않는다. */
    val fragmentCount: Int,
    /** 각 조각의 최대 글자 길이(형태 상한, 내용 아님). > 0. */
    val maxFragmentLength: Int,
    /** 발화 대신 reaction 만 할지. true 면 speech 는 빈 발화(IGNORE/REACT 하강)로 처리될 수 있다. */
    val reactionOnly: Boolean,
) {
    init {
        require(fragmentCount >= 1) { "fragmentCount 는 1 이상이어야 한다: $fragmentCount" }
        require(maxFragmentLength > 0) { "maxFragmentLength 는 양수여야 한다: $maxFragmentLength" }
    }
}
