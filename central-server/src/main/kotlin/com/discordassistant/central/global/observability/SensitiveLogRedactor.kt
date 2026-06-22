package com.discordassistant.central.global.observability

/**
 * 로그 redaction(NEXA-P17-T013, security).
 * SSOT: [redaction-contract.md](../../../../../../../../docs/nexa/security/redaction-contract.md).
 *
 * 로그 라인에서 **금지 문자열(Discord snowflake·API key·Bearer 토큰)** 을 마스킹한다. 운영 코드가 메시지 원문·
 * snowflake·키를 실수로 로그에 흘려도, 이 redactor 를 거치면 PII·비밀이 남지 않는다. [scan-sensitive-logs.py]
 * 스캐너가 같은 패턴으로 로그 파일을 검사해 금지 문자열이 한 건이라도 있으면 CI 를 실패시킨다(acceptance T013).
 *
 * 가명 라벨(user_3 등 짧은 숫자)·status·correlationId 같은 허용 필드는 영향받지 않는다(redaction-contract 허용 필드).
 */
object SensitiveLogRedactor {
    /** Discord snowflake: 17~20자리 연속 숫자(가명 user_3 등은 미해당). */
    val SNOWFLAKE_RE = Regex("""\b\d{17,20}\b""")

    /** 흔한 API key 토큰: sk-…(OpenAI 류), AIza…(Google 류). */
    val API_KEY_RE = Regex("""\b(?:sk-[A-Za-z0-9]{16,}|AIza[A-Za-z0-9_\-]{16,})\b""")

    /** Authorization Bearer 토큰. */
    val BEARER_RE = Regex("""(?i)Bearer\s+[A-Za-z0-9._\-]{8,}""")

    /** 한 로그 라인을 redaction 한다(금지 문자열 마스킹). */
    fun redact(line: String): String =
        line
            .replace(BEARER_RE, "[redacted-token]")
            .replace(API_KEY_RE, "[redacted-key]")
            .replace(SNOWFLAKE_RE, "[redacted-id]")

    /** [line] 에 금지 문자열(snowflake·키·토큰)이 남아 있는지(스캐너·테스트 self-check). */
    fun containsSensitive(line: String): Boolean =
        SNOWFLAKE_RE.containsMatchIn(line) ||
            API_KEY_RE.containsMatchIn(line) ||
            BEARER_RE.containsMatchIn(line)
}
