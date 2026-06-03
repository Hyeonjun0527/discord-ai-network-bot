package com.discordassistant.central.domain

/**
 * 공개 프리셋 임포트 기록(`preset_import`) 상태.
 *
 * 값 집합은 코드의 실제 status 리터럴에서 도출했다(`PresetRegistryService`):
 * - 대상 채널 없이 임포트(detached copy)하면 기본 `imported`.
 * - 대상 채널에 적용(`applyRevisionToChannel`)하면 안전등급에 따라
 *   `applied`(정상 적용) 또는 `needs_review`(고위험 → 변경 제안 생성).
 *   임포트 엔티티는 `applied?.status ?: "imported"` 로 이 값을 그대로 저장한다.
 *
 * 와이어/DB 표현은 소문자([wire]) 로 유지해 기존 JSON 응답·DB VARCHAR 와 동일하다. 마이그레이션 불필요.
 *
 * 임포트 기록은 한 번 저장된 뒤 status 가 다시 바뀌는 코드 경로가 없으므로(생성 시점에 확정)
 * 전이맵([ALLOWED])은 비어 있다 — 모든 값이 종단 상태다(자기 자신으로의 동일 전이만 허용).
 */
enum class PresetImportStatus(
    val wire: String,
) {
    /** 채널 적용 없이 길드 사본으로만 임포트됨(detached). */
    IMPORTED("imported"),

    /** 대상 채널에 즉시 적용됨. */
    APPLIED("applied"),

    /** 고위험 안전등급으로 변경 제안(검수) 대기 상태로 임포트됨. */
    NEEDS_REVIEW("needs_review"),
    ;

    /** 이 상태에서 [next] 로 전이가 허용되는가. 임포트 기록은 생성 시 확정되어 동일 전이만 허용. */
    fun canTransitionTo(next: PresetImportStatus): Boolean = next == this

    companion object {
        private val BY_WIRE: Map<String, PresetImportStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 알 수 없는/깨진 값은 견고하게 [IMPORTED] 로 폴백(엔티티 기본값). */
        fun fromWire(value: String?): PresetImportStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: IMPORTED
    }
}
