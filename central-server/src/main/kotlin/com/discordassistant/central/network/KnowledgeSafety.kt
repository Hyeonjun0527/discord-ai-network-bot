package com.discordassistant.central.network

object KnowledgeSafety {
    private val secretAssignmentPattern = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")
    private val sensitivePatterns =
        listOf(
            Regex("(?i)\\b(password|passwd|pwd)\\s*[:=]\\s*\\S+"),
            Regex("(?i)\\b(api[_-]?key|secret|token|bot[_-]?token|private[_-]?key)\\s*[:=]\\s*\\S+"),
            Regex("(?i)-----BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----"),
            Regex("(?i)discord[_-]?bot[_-]?token"),
            Regex("(?i)sk-[A-Za-z0-9_-]{20,}"),
            Regex("""[A-Za-z0-9_\-]{20,}\.[A-Za-z0-9_\-]{6,}\.[A-Za-z0-9_\-]{20,}"""),
        )
    private val sensitiveKeywordPatterns =
        listOf(
            Regex("""(?i)\b(password|passwd|pwd|secret|authorization|bearer)\b"""),
            Regex("""(?i)\b(api[_-]?key|token|bot[_-]?token|private[_-]?key)\s*[:=]\s*\S+"""),
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
            Regex("""sk-[A-Za-z0-9_-]{20,}"""),
        )

    /**
     * 채널 AI 지침/페르소나 입력에서 차단할 위험어(프롬프트 인젝션·민감정보 수집·권한 탈취 의도)의 **단일 출처**.
     * [ChannelAiCustomizationService.approvalDecision](관리자 마법사) 과 [OnboardingAnalyzer](LLM 출력 1차 가드)
     * 가 같은 집합을 공유해 drift 를 막는다. substring 매칭이라 완벽한 우회 차단은 불가하며(사람 승인이 backstop),
     * 한글/영어 변형을 가능한 한 망라한다.
     */
    val RISKY_INSTRUCTION_TERMS: Set<String> =
        setOf(
            // 영어 — 프롬프트 인젝션/탈옥
            "ignore previous",
            "ignore safety",
            "ignore the rules",
            "ignore all",
            "disregard",
            "bypass",
            "jailbreak",
            "override safety",
            // 한글 — 안전장치 무시/우회
            "이전 지시 무시",
            "이전 지시를 무시",
            "안전 규칙 무시",
            "안전 규칙을 무시",
            "안전장치 무시",
            "안전장치를 무시",
            "안전 무시",
            "지침 무시",
            "규칙 무시",
            // 민감정보 수집/요구
            "토큰을 알려",
            "토큰 수집",
            "비밀번호를 알려",
            "비밀번호 수집",
            "개인키를 알려",
            "개인정보 수집",
            // 권한 탈취
            "관리자 권한",
        )

    /** 위험어가 하나라도 포함되면 true(대소문자 무시·substring 매칭). 빈 값은 false. */
    fun looksRiskyInstruction(value: String?): Boolean {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return false
        return RISKY_INSTRUCTION_TERMS.any { it.lowercase() in text }
    }

    fun containsSensitiveMaterial(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return false
        return sensitivePatterns.any { it.containsMatchIn(text) }
    }

    fun looksSensitiveQuery(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return false
        return sensitiveKeywordPatterns.any { it.containsMatchIn(text) }
    }

    fun redactReason(value: String): String =
        value
            .trim()
            .replace(secretAssignmentPattern, "[redacted]")
            .take(80)
            .ifBlank { "manual" }
}
