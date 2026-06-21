package com.discordassistant.central.actionruntime.application.port.out

/**
 * 발화 본문 참조 → 실제 문구 resolver 포트(NEXA-P13-T017, application 레이어).
 *
 * 전송 명령은 원문 본문이 아니라 **참조**(speechPlanRef)만 운반한다(원문 보호 경계 — T003 일관). 실제 전송 직전에
 * 이 포트가 참조를 본문으로 푼다. 구현은 생성된 content 저장소(`nexa_scheduled_action_content`)에서 읽는다. 참조에
 * 해당하는 본문이 없으면(미생성/만료) null 을 돌려준다 — 호출자는 전송하지 않고 우아하게 종결한다.
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
fun interface SpeechContentResolver {
    /** [speechPlanRef] 의 실제 발화 본문을 돌려준다(없으면 null — 미생성/만료). */
    fun resolve(speechPlanRef: String): String?
}
