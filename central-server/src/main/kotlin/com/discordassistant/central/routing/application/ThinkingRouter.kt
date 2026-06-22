package com.discordassistant.central.routing.application

/**
 * z.ai GLM thinking(추론) 속도 라우터 — **규칙 기반(LLM 호출 0)**. 추론이 필요해 보이는 질문이면
 * [CloudThinking.ENABLED](깊은 추론·느림·정확), 짧은 사실/잡담이면 [CloudThinking.DISABLED](즉답·빠름).
 *
 * **기본은 DISABLED(속도 우선)** — 추론 키워드가 있거나 길이 임계를 넘을 때만 ENABLED 로 올린다.
 * 순수 함수라 외부 의존 없이 단위테스트 가능하다.
 */
object ThinkingRouter {
    /** 추론을 시사하는 키워드(있으면 thinking ENABLED). 한국어/영어 혼용 질문을 모두 잡는다. */
    private val REASONING_KEYWORDS =
        listOf(
            "왜",
            "어떻게",
            "계산",
            "단계별",
            "코드",
            "증명",
            "비교",
            "분석",
            "풀이",
            "why",
            "how",
            "calculate",
            "step by step",
            "step-by-step",
            "prove",
            "proof",
            "compare",
            "analyze",
            "analyse",
            "solve",
            "debug",
            "algorithm",
            "알고리즘",
        )

    /** 이 길이(문자) 이상이면 복잡한 질문으로 보고 thinking ENABLED. */
    private const val LENGTH_THRESHOLD = 180

    /**
     * 질문 [prompt] 로부터 thinking 모드를 정한다. 추론 키워드 포함 또는 길이 임계 초과면 ENABLED, 아니면 DISABLED.
     */
    fun route(prompt: String): CloudThinking {
        val text = prompt.trim()
        if (text.length >= LENGTH_THRESHOLD) return CloudThinking.ENABLED
        val lower = text.lowercase()
        if (REASONING_KEYWORDS.any { lower.contains(it) }) return CloudThinking.ENABLED
        return CloudThinking.DISABLED
    }
}

/**
 * 어드민 /질문 thinking override 슬래시 옵션(문자열)을 [CloudThinking] 으로 파싱한다.
 * "on"/"enabled"/"true"=ENABLED, "off"/"disabled"/"false"=DISABLED, 그 외/빈값=null(=라우터 자동).
 */
object CloudThinkingOption {
    fun parse(value: String?): CloudThinking? =
        when (value?.trim()?.lowercase()) {
            "on", "enabled", "enable", "true", "yes" -> CloudThinking.ENABLED
            "off", "disabled", "disable", "false", "no" -> CloudThinking.DISABLED
            else -> null
        }
}
