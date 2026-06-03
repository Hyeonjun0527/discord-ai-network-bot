package com.discordassistant.central.domain

/**
 * 채널 지식공간(`knowledge_space`) 상태머신.
 *
 * 기존에는 bare `String`("draft"/"pending_index"/"needs_review"/"ready") 이던 것을 도메인 enum 으로
 * 풍부화한다. DB 컬럼과 응답 JSON 은 **소문자 와이어 값**([wire]) 을 그대로 유지하므로 마이그레이션이나
 * 외부 계약 변경이 없다(동작 불변).
 *
 * 값 집합은 코드가 실제로 대입하는 status 리터럴에서 전수 도출했다
 * (`KnowledgeIngestionService`/`KnowledgeIndexingService`):
 * - 생성 시 `draft`.
 * - 소스 추가 시 `pending_index`(pending 소스) 또는 `needs_review`(blocked/위험 소스).
 * - 색인 완료 시 `ready`.
 * - 소스 삭제(tombstone) 후 잔여 소스에 따라 `draft`/`needs_review`/`pending_index`/`ready` 로 재산정.
 *
 * 전이맵([ALLOWED])은 위 실제 전이의 합집합이라 사실상 어떤 상태에서든 다른 상태로 재산정될 수 있다.
 */
enum class KnowledgeSpaceStatus(
    val wire: String,
) {
    /** 막 생성됨(소스 없음). */
    DRAFT("draft"),

    /** 색인 대기 소스가 있어 재색인이 필요함. */
    PENDING_INDEX("pending_index"),

    /** 차단/위험 소스가 있어 운영자 검토가 필요함. */
    NEEDS_REVIEW("needs_review"),

    /** 색인된 소스가 있어 검색 준비 완료. */
    READY("ready"),
    ;

    /** 검색에 사용할 수 있는 준비 완료 상태인가. */
    val isReady: Boolean get() = this == READY

    /** 이 상태에서 [next] 로 전이가 허용되는가(실제 코드 전이에서 도출한 가드). */
    fun canTransitionTo(next: KnowledgeSpaceStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        // 색인/삭제 흐름이 잔여 소스에 따라 어떤 상태로든 재산정하므로 전이는 사실상 전체 허용.
        private val ALLOWED: Map<KnowledgeSpaceStatus, Set<KnowledgeSpaceStatus>> =
            entries.associateWith { current -> entries.filterNot { it == current }.toSet() }

        private val BY_WIRE: Map<String, KnowledgeSpaceStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 알 수 없는/깨진 값은 견고하게 [DRAFT] 로 폴백. */
        fun fromWire(value: String?): KnowledgeSpaceStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: DRAFT
    }
}
