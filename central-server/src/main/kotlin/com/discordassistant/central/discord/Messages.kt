package com.discordassistant.central.discord

/**
 * i18n 메시지 리소스(차수 11 #157). 핵심 사용자 대면 문자열의 한/영 번들.
 * 길드 언어(`policy.guildLanguage`)로 조회하며, 미지원 언어/키는 ko 로 폴백한다.
 */
object Messages {
    enum class Key { PRIVACY_NOTICE, COOLDOWN, NO_PROVIDER, ADMIN_DENIED }

    private val ko = mapOf(
        Key.PRIVACY_NOTICE to
            "이 서버는 커뮤니티 로컬 AI Provider Pool 을 사용합니다. 질문 내용은 요청을 처리하는 " +
            "커뮤니티 프로바이더의 PC 로 전송될 수 있습니다. 비밀번호·API 키·개인정보·비공개 문서 등 " +
            "민감한 정보는 입력하지 마세요.",
        Key.COOLDOWN to "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.",
        Key.NO_PROVIDER to "현재 풀에 온라인 프로바이더가 없습니다.",
        Key.ADMIN_DENIED to "이 명령은 관리자만 사용할 수 있습니다.",
    )

    private val en = mapOf(
        Key.PRIVACY_NOTICE to
            "This server uses a community local AI Provider Pool. Your question may be sent to a " +
            "community provider's PC for processing. Do not enter sensitive information such as " +
            "passwords, API keys, personal data, or private documents.",
        Key.COOLDOWN to "Too many requests. Please try again shortly.",
        Key.NO_PROVIDER to "No online providers are available in the pool right now.",
        Key.ADMIN_DENIED to "This command is for administrators only.",
    )

    fun get(key: Key, language: String): String {
        val table = if (language.equals("en", ignoreCase = true)) en else ko
        return table[key] ?: ko[key] ?: key.name
    }
}
