package com.discordassistant.central.knowledge.domain.model

/**
 * 채널/지식공간 검색(RAG) 정책(`retrieval_policy`) 상태.
 *
 * 값 집합은 코드의 실제 status 리터럴에서 도출했다(`KnowledgeIndexingService`,
 * `KnowledgeSearchService`): 정책 upsert 시 항상 `active` 로 저장하고, 조회도
 * `findBy...AndStatus("active")` 로 활성 정책만 읽는다. 비활성화(`disabled`)는 와이어 계약상
 * 예약된 값이며 현재 코드에는 비활성화 전이가 없다.
 *
 * 와이어/DB 표현은 소문자([wire]) 로 유지해 기존 DB VARCHAR·파생 쿼리 필터와 동일하다.
 * 마이그레이션 불필요.
 *
 * 전이맵([ALLOWED]): 현재 코드는 항상 `ACTIVE` 만 쓰지만, 향후 비활성화/재활성화를 거부하지
 * 않도록 `ACTIVE`↔`DISABLED` 양방향을 허용한다(어떤 기존 전이도 거부하지 않는다).
 */
enum class RetrievalPolicyStatus(
    val wire: String,
) {
    /** 활성 검색 정책(조회 대상). */
    ACTIVE("active"),

    /** 비활성 검색 정책(예약 값; 현재 코드 경로 없음). */
    DISABLED("disabled"),
    ;

    /** 검색 시 적용되는 활성 정책인가. */
    val isActive: Boolean get() = this == ACTIVE

    /** 이 상태에서 [next] 로 전이가 허용되는가. */
    fun canTransitionTo(next: RetrievalPolicyStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        private val ALLOWED: Map<RetrievalPolicyStatus, Set<RetrievalPolicyStatus>> =
            mapOf(
                ACTIVE to setOf(DISABLED),
                DISABLED to setOf(ACTIVE),
            )

        private val BY_WIRE: Map<String, RetrievalPolicyStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 알 수 없는/깨진 값은 견고하게 [ACTIVE] 로 폴백(엔티티 기본값). */
        fun fromWire(value: String?): RetrievalPolicyStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: ACTIVE
    }
}
