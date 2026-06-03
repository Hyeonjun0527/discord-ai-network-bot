package com.discordassistant.central.domain

/**
 * 길드 소유 AI 프리셋(`ai_preset`) 상태.
 *
 * 값 집합은 코드의 실제 status 리터럴(`grep "status = \"..\""`)에서 도출했다:
 * 생성 시 `draft`, 게시 시 `published`, 삭제 시 `removed`.
 *
 * 와이어/DB 표현은 소문자([wire]) 로 유지해 기존 JSON 응답·DB VARCHAR 와 동일하다.
 * 전이는 [canTransitionTo] 가드가 강제하며, 전이맵은 PresetRegistryService 의 실제 전이에서 도출했다:
 * - publish: DRAFT → PUBLISHED
 * - delete: any(비-terminal) → REMOVED (deletePreset 은 가드 없이 항상 허용)
 */
enum class PresetStatus(
    val wire: String,
) {
    DRAFT("draft"),
    PUBLISHED("published"),
    REMOVED("removed"),
    ;

    /** 더 이상 전이가 없는 종료 상태. removed 프리셋은 변경 불가(requireActivePreset). */
    val isTerminal: Boolean get() = this == REMOVED

    /** 이 상태에서 [next] 로 전이가 허용되는가(PresetRegistryService 가드). */
    fun canTransitionTo(next: PresetStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        private val ALLOWED: Map<PresetStatus, Set<PresetStatus>> =
            mapOf(
                DRAFT to setOf(PUBLISHED, REMOVED),
                PUBLISHED to setOf(REMOVED),
                REMOVED to emptySet(),
            )

        private val BY_WIRE: Map<String, PresetStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 알 수 없는/깨진 값은 견고하게 [DRAFT] 로 폴백. */
        fun fromWire(value: String?): PresetStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: DRAFT
    }
}
