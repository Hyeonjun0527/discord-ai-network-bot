package com.discordassistant.central.knowledge.domain.model

/**
 * 임베딩 청크(`knowledge_chunk`) 상태머신.
 *
 * 기존에는 bare `String`("ready"/"superseded"/"deleted") 이던 것을 도메인 enum 으로 풍부화한다.
 * DB 컬럼과 응답 JSON 은 **소문자 와이어 값**([wire]) 을 그대로 유지하므로 마이그레이션이나 외부 계약
 * 변경이 없다(동작 불변).
 *
 * 값 집합은 코드의 실제 status 리터럴에서 전수 도출했다(`KnowledgeIndexingService`):
 * - 청크 생성 시 `ready`(검색 가능).
 * - 재색인으로 기존 청크를 `superseded` 로 대체.
 * - 소스 삭제 tombstone 시 `deleted`.
 *
 * `ready` 청크만 검색/임베딩 대상이다(findByKnowledgeSpaceIdAndStatus("ready")).
 */
enum class KnowledgeChunkStatus(
    val wire: String,
) {
    /** 검색/임베딩 대상이 되는 활성 청크. */
    READY("ready"),

    /** 재색인으로 더 새로운 청크에 의해 대체됨. */
    SUPERSEDED("superseded"),

    /** 소스 삭제로 tombstone 처리됨. */
    DELETED("deleted"),
    ;

    /** 검색/임베딩 대상이 되는가. */
    val isReady: Boolean get() = this == READY

    /** 더 이상 나가는 전이가 없는 종단 상태인가. */
    val isTerminal: Boolean get() = this == DELETED

    /** 이 상태에서 [next] 로 전이가 허용되는가(실제 코드 전이에서 도출한 가드). */
    fun canTransitionTo(next: KnowledgeChunkStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        private val ALLOWED: Map<KnowledgeChunkStatus, Set<KnowledgeChunkStatus>> =
            mapOf(
                READY to setOf(SUPERSEDED, DELETED),
                SUPERSEDED to setOf(DELETED),
                DELETED to emptySet(),
            )

        private val BY_WIRE: Map<String, KnowledgeChunkStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 알 수 없는/깨진 값은 견고하게 [READY] 로 폴백. */
        fun fromWire(value: String?): KnowledgeChunkStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: READY
    }
}
