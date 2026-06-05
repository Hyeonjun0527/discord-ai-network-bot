package com.discordassistant.central.preset.domain.model

/**
 * 공개 프리셋(`published_preset`) 카탈로그 상태.
 *
 * 값 집합은 코드의 실제 status 리터럴에서 도출했다: 게시 시 `published`,
 * 신고 임계 도달 시 `under_review`, 검수 결정으로 `suspended`/`removed`,
 * 운영자가 목록에서 내릴 때 `unlisted`.
 *
 * 와이어/DB 표현은 소문자([wire]) 로 유지해 기존 JSON 응답·DB VARCHAR·검색/필터/moderation
 * 그룹핑과 동일하다. 전이맵은 PresetRegistryService 의 실제 전이에서 도출했다:
 * - publish: (초기) PUBLISHED
 * - report 임계: PUBLISHED → UNDER_REVIEW
 * - reviewReport suspend: → SUSPENDED, remove: → REMOVED, dismiss: UNDER_REVIEW → PUBLISHED
 * - unlist: removed 가 아니면 → UNLISTED
 * - republish: UNLISTED → PUBLISHED (코드상 unlisted 만 허용)
 * - delete: any(비-terminal) → REMOVED (deletePublishedPreset 은 가드 없이 항상 허용)
 */
enum class PublishedPresetStatus(
    val wire: String,
) {
    PUBLISHED("published"),
    UNDER_REVIEW("under_review"),
    SUSPENDED("suspended"),
    UNLISTED("unlisted"),
    REMOVED("removed"),
    ;

    /** 카탈로그/추천/like·import·report 대상이 되는 활성 게시 상태. */
    val isActive: Boolean get() = this == PUBLISHED

    /** 더 이상 전이가 없는 종료 상태. */
    val isTerminal: Boolean get() = this == REMOVED

    /** 이 상태에서 [next] 로 전이가 허용되는가(PresetRegistryService 가드). */
    fun canTransitionTo(next: PublishedPresetStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        private val ALLOWED: Map<PublishedPresetStatus, Set<PublishedPresetStatus>> =
            mapOf(
                // report→under_review, suspend, unlist(non-removed), remove
                PUBLISHED to setOf(UNDER_REVIEW, SUSPENDED, UNLISTED, REMOVED),
                // dismiss→published, suspend, unlist, remove
                UNDER_REVIEW to setOf(PUBLISHED, SUSPENDED, UNLISTED, REMOVED),
                // reviewReport 재적용(suspend self), unlist(non-removed), remove
                SUSPENDED to setOf(SUSPENDED, UNLISTED, REMOVED),
                // republish→published, reviewReport(suspend), remove
                UNLISTED to setOf(PUBLISHED, SUSPENDED, REMOVED),
                REMOVED to emptySet(),
            )

        private val BY_WIRE: Map<String, PublishedPresetStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 알 수 없는/깨진 값은 견고하게 [PUBLISHED] 로 폴백. */
        fun fromWire(value: String?): PublishedPresetStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: PUBLISHED
    }
}
