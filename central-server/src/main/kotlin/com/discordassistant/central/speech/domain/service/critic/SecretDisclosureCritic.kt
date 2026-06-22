package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 비밀 비노출 비평가(NEXA-P17-T003, security·순수 도메인 서비스).
 *
 * 생성된 발화 후보에 **시스템 지침·API 키 패턴·내부 schema·hidden ID** 가 섞여 있으면 후보를 폐기한다.
 * 사용자가 injection 으로 "시스템 프롬프트를 말해봐" 를 유도하거나, 모델이 실수로 내부 비밀을 토해내는
 * 경우를 생성 후 마지막에 거른다. 평가-전용 계약([SpeechCritic]) — 후보를 고치지 않고 [CriticVerdict.reject] 만 한다.
 *
 * **acceptance(T003) — 가짜 비밀 fixture 와 실제 환경 변수명이 모두 테스트된다**:
 *  - API 키 패턴(sk-…, AIza…, Bearer …)·snowflake(17~20자리)·실제 env 변수명([SECRET_ENV_NAMES])·
 *    시스템 지침 마커([SYSTEM_INSTRUCTION_MARKERS])·내부 schema 토큰([INTERNAL_SCHEMA_MARKERS]) 중
 *    하나라도 후보에 있으면 [CriticReason.SECRET_DISCLOSURE] 로 탈락한다.
 *  - 사유 메시지에 탐지된 비밀 원문을 담지 않는다(boolean/enum 만 — 로그 누출 방지).
 *
 * 순수성: Spring/JPA/JDA 미참조. speech 도메인 타입·표준 타입만.
 */
class SecretDisclosureCritic : SpeechCritic {
    override fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict =
        if (disclosesSecret(candidate.joined)) CriticVerdict.reject(CriticReason.SECRET_DISCLOSURE) else CriticVerdict.ACCEPTED

    /**
     * [text] 에 비밀(키 패턴·snowflake·env 변수명·시스템 지침·내부 schema)이 노출됐는지 검사한다.
     * 탐지된 원문은 반환하지 않는다(boolean 만).
     */
    fun disclosesSecret(text: String): Boolean {
        if (API_KEY_RE.containsMatchIn(text)) return true
        if (BEARER_RE.containsMatchIn(text)) return true
        if (SNOWFLAKE_RE.containsMatchIn(text)) return true
        val upper = text.uppercase()
        if (SECRET_ENV_NAMES.any { upper.contains(it) }) return true
        if (SYSTEM_INSTRUCTION_MARKERS.any { text.contains(it) }) return true
        if (INTERNAL_SCHEMA_MARKERS.any { text.contains(it) }) return true
        return false
    }

    companion object {
        /** 흔한 API key 토큰: sk-…(OpenAI 류), AIza…(Google 류), glm/zai 키 류. */
        val API_KEY_RE = Regex("""\b(?:sk-[A-Za-z0-9]{16,}|AIza[A-Za-z0-9_\-]{16,})\b""")

        /** Authorization Bearer 토큰. */
        val BEARER_RE = Regex("""(?i)Bearer\s+[A-Za-z0-9._\-]{8,}""")

        /** Discord snowflake(17~20자리 연속 숫자) — hidden ID 누출. */
        val SNOWFLAKE_RE = Regex("""\b\d{17,20}\b""")

        /** 실제 운영 환경 변수명 — 후보가 변수명 자체를 말하면 내부 구조 누출 신호. */
        val SECRET_ENV_NAMES: List<String> =
            listOf(
                "NEXA_FIELD_ENC_KEY",
                "GLM_API_KEY",
                "ZAI_API_KEY",
                "DISCORD_BOT_TOKEN",
                "CENTRAL_DASHBOARD_ADMIN",
                "DURABLE_TOKEN",
            )

        /** 시스템 지침이 후보에 새어 나왔음을 보이는 마커(영문/한글). */
        val SYSTEM_INSTRUCTION_MARKERS: List<String> =
            listOf(
                "[시스템 지침]",
                "system prompt",
                "System Prompt",
                "당신은 「니아」",
                "너는 「니아」",
                "<<SYS>>",
                "identity kernel",
            )

        /** 내부 schema·테이블·필드명 토큰 — DB/스키마 구조 누출 신호. */
        val INTERNAL_SCHEMA_MARKERS: List<String> =
            listOf(
                "pseudonym_key",
                "correlationId=",
                "decisionId=",
                "event_store",
                "socialmemory.",
                "SELECT * FROM",
            )
    }
}
