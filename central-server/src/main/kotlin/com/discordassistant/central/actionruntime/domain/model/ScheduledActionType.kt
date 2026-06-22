package com.discordassistant.central.actionruntime.domain.model

/**
 * 예약 가능한 사회적 행동 종류(NEXA-P13-T001, 순수 도메인 enum·안정 코드).
 *
 * participation 이 낸 [com.discordassistant.central.participation.domain.model.action.SocialAction] 중 **예약·실행
 * 대상**(시간을 두고 due 시점에 실행)을 actionruntime 관점으로 분류한 안정 코드다. IGNORE 는 아무것도 하지
 * 않으므로 예약하지 않는다(애초에 [ScheduledSocialAction] 으로 들어오지 않는다).
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
enum class ScheduledActionType(
    /** decision log·persistence 직렬화용 안정 코드(enum 이름 변경에도 와이어 호환). */
    val wireName: String,
    /** 이 행동이 실제 발화(SPEAK)인가 — 취소 정책(T012~T014)이 SPEAK 만 stale 취소 후보로 본다. */
    val isSpeech: Boolean,
) {
    /** 발화(SPEAK) — speech 가 만든 문구를 due 시점에 전송. 취소 정책의 주 대상. */
    SPEAK("speak", isSpeech = true),

    /** 리액션(REACT) — 발화 없이 reaction 코드만. */
    REACT("react", isSpeech = false),
}
