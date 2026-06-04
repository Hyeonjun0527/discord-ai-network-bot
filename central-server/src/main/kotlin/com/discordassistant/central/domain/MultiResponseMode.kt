package com.discordassistant.central.domain

/**
 * 다중응답 정책 모드(`/ai-multi-response-set` 의 `mode` 옵션).
 *
 * 이전엔 `single`/`compare`/`debate` 문자열이 슬래시 카탈로그에 하드코딩되고 `MultiResponseService`
 * 가 `mode.trim().lowercase().ifBlank { "single" }` 로 별도 정규화했다. 값 집합·라벨·정규화를 이 enum
 * 으로 단일화한다(SSOT). 와이어/DB 표현은 기존 소문자 값([wire])을 유지 — 마이그레이션 불필요.
 */
enum class MultiResponseMode(
    val wire: String,
    /** Discord 슬래시 옵션 등 사용자 표시용 한글 라벨. */
    val label: String,
) {
    /** 단일 답변(기본). */
    SINGLE("single", "단일 답변"),

    /** 여러 후보를 만들어 비교. */
    COMPARE("compare", "후보 비교"),

    /** 서로 다른 관점으로 토론형 비교. */
    DEBATE("debate", "관점 비교"),
    ;

    companion object {
        /** 슬래시 옵션 choice 용 (라벨, 와이어값) 목록 — SSOT. */
        fun slashChoices(): List<Pair<String, String>> = entries.map { it.label to it.wire }

        private val BY_WIRE: Map<String, MultiResponseMode> = entries.associateBy { it.wire }

        /** 소문자 와이어 문자열 → enum. 알 수 없는/빈 값은 [SINGLE] 로 폴백(기존 `ifBlank{"single"}` 동작). */
        fun fromWire(value: String?): MultiResponseMode = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: SINGLE
    }
}
