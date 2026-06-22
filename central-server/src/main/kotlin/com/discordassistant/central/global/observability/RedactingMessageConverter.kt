package com.discordassistant.central.global.observability

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * 로그 메시지 redaction 컨버터(NEXA-P17-T013 enforcement, security).
 *
 * logback 의 `%msg`(formatted message)를 [SensitiveLogRedactor] 로 통과시켜, 운영 코드가 실수로 Discord
 * snowflake·API key·Bearer 토큰을 로그에 흘려도 파일/콘솔에 PII·비밀이 남지 않게 한다. logback-spring.xml 의
 * `<conversionRule>` 로 표준 `%msg` 토큰을 이 컨버터로 바인딩한다 — 즉 redactor 가 실제 로그 경로에 연결된다
 * (security-reviewer H3 갭: SensitiveLogRedactor 가 logback 에 참조되지 않던 문제 해소).
 *
 * 패턴 분리: 컨버터가 자기 자신을 예외로 떨어뜨려 로그를 잃지 않도록, redaction 실패는 원문 메시지로 안전 폴백한다.
 */
class RedactingMessageConverter : MessageConverter() {
    override fun convert(event: ILoggingEvent): String {
        val formatted = super.convert(event)
        return try {
            SensitiveLogRedactor.redact(formatted)
        } catch (e: RuntimeException) {
            // redaction 자체가 실패하면(이론상 없음) 로그를 잃지 않되, 안전을 위해 보수적으로 마스킹 마커를 남긴다.
            "[redaction-error:${e.javaClass.simpleName}]"
        }
    }
}
