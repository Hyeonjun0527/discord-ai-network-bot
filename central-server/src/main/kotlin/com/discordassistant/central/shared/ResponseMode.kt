package com.discordassistant.central.shared

/**
 * 응답 모드(답변 깊이/속도)의 단일 진실 원천(SSOT).
 *
 * 한국어 별칭 포함 정규화 로직 `normalizeResponseMode(...)` 가 4개 서비스
 * (ChannelAiRoutingPolicy / PresetRegistry / KnowledgeSearch / ProviderSafety)와
 * `CommandService.normalizeAskResponseMode` 에 거의 동일하게 복제돼 있던 것을 이 enum 으로 모은다.
 *
 * 입력은 String 뿐이라 ArchUnit `domainIsIndependent` 를 위반하지 않는다. 와이어 값([wire])은
 * 기존 소문자 값을 보존한다. (KnowledgeSearch 의 `"off"` 모드는 RAG 전용이라 호출부에서 별도 처리.)
 */
enum class ResponseMode(
    val wire: String,
    /** Discord 슬래시 옵션 등 사용자 표시용 한글 라벨(SSOT). 카탈로그가 재하드코딩하지 않게 여기서 보유. */
    val label: String,
) {
    FAST("fast", "빠른 답변"),
    BALANCED("balanced", "균형 모드"),
    DEEP("deep", "깊은 답변"),
    SAVING("saving", "절약 모드"),
    ;

    companion object {
        /** 슬래시 옵션 choice 용 (라벨, 와이어값) 목록 — SSOT(표시 순서는 enum 선언 순). */
        fun slashChoices(): List<Pair<String, String>> = entries.map { it.label to it.wire }

        /** 한국어/영문 별칭 포함 정규화. 인식 못 하는 값(빈 값 포함)은 null. */
        fun normalizeOrNull(value: String?): ResponseMode? =
            when (value?.trim()?.lowercase()) {
                "fast", "빠른", "빠른 답변" -> FAST
                "balanced", "균형", "균형 모드" -> BALANCED
                "deep", "깊은", "깊은 답변" -> DEEP
                "saving", "economy", "절약", "절약 모드" -> SAVING
                else -> null
            }

        /** 정규화하되 인식 못 하면 [BALANCED] 기본값. 기존 `normalizeResponseMode` 의 동작. */
        fun normalize(value: String?): ResponseMode = normalizeOrNull(value) ?: BALANCED
    }
}
