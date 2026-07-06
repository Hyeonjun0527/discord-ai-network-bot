package com.discordassistant.central.actionruntime.application.port.out

/**
 * 생성된 발화 본문 참조 → 저장 아웃바운드 포트(NEXA-P13-T003 원문 보호 경계, application 레이어).
 *
 * 발화 후보가 확정돼 SPEAK 가 예약되는 시점에, 실제 문구를 참조(speechPlanRef)로 저장한다. 전송 명령은 원문이
 * 아니라 이 참조만 운반하고([SpeechContentResolver] 의 역방향), 실제 전송 직전에 resolver 가 참조를 본문으로 푼다.
 * 구현은 생성된 content 저장소(`nexa_scheduled_action_content`)에 idempotent 하게 쓴다(같은 참조 재저장은 no-op).
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
fun interface SpeechContentWriter {
    /** [speechPlanRef] 의 발화 본문 [content] 를 저장한다(같은 참조가 이미 있으면 멱등 no-op — 원문 이중 저장 금지). */
    fun store(
        speechPlanRef: String,
        content: String,
    )
}
