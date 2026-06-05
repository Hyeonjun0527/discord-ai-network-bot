package com.discordassistant.central.shared

/**
 * 콘텐츠 안전 등급/플래그 어휘의 단일 진실 원천(SSOT).
 *
 * 같은 `setOf(...)` 가 서비스마다 손수 복제돼 있던 것을 한곳으로 모은다(이중화 제거):
 * - `HIGH_RISK_SAFETY_LEVELS` — PresetRegistry / PresetCatalogQuery / ChannelAiCustomization 3곳 동일
 * - `BLOCKING_SAFETY_FLAGS` — MultiResponse / MultiResponseReporting 2곳 동일
 * - `USABLE_KNOWLEDGE_RISK_LEVELS` — KnowledgeIngestion(INDEXABLE) / KnowledgeSearch(SEARCHABLE) 동일 값
 *
 * 순수 상수(String 집합)뿐이라 ArchUnit `domainIsIndependent` 를 위반하지 않는다. 값은 기존과 동일.
 */
object ContentSafety {
    /** 프리셋/채널 AI 의 게시·적용을 추가 검토/차단해야 하는 고위험 안전 등급. */
    val HIGH_RISK_SAFETY_LEVELS = setOf("high", "restricted", "dangerous")

    /** 다중응답 후보 답변을 즉시 제외해야 하는 안전 플래그. */
    val BLOCKING_SAFETY_FLAGS = setOf("unsafe", "policy_violation", "sensitive", "blocked", "jailbreak")

    /** 지식 소스를 색인/검색에 사용할 수 있는 위험 등급. */
    val USABLE_KNOWLEDGE_RISK_LEVELS = setOf("normal", "review")

    /** 비밀값(키/토큰/비밀번호 등) 노출 탐지·레닥션 정규식. */
    val SECRET_PATTERN = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")

    /** 민감 프롬프트(자격증명/개인키 등) 탐지 정규식 목록. */
    val SENSITIVE_PROMPT_PATTERNS =
        listOf(
            Regex("""(?i)\b(password|passwd|pwd|secret)\b"""),
            Regex("(?i)(api[_-]?key|bot[_-]?token|discord[_-]?bot[_-]?token|private[_-]?key|access[_-]?token)"),
            Regex("(?i)-----BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----"),
            Regex("(?i)sk-[A-Za-z0-9_-]{20,}"),
        )
}
