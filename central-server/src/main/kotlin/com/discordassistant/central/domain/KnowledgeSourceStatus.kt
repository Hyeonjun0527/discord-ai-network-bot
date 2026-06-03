package com.discordassistant.central.domain

/**
 * 지식 소스(`knowledge_source`) 상태.
 *
 * 다른 Knowledge status 와 달리 와이어 표현이 **단순 enum 이 아니다**. 두 종류가 섞여 있다:
 * 1. 고정 토큰 상태(`pending`/`indexed`/`review` 및 여러 `blocked_*` 변종).
 * 2. **사유가 콜론으로 붙는 동적 상태** — `rejected:<사유>`, `deleted:<사유>`
 *    (`KnowledgeIngestionService.rejectSource`/`removeSource`).
 *
 * 따라서 bare `String` 을 단일 enum 으로 바꾸면 사유 페이로드가 사라져 DB/JSON 바이트가 변한다.
 * 대신 [kind] 도메인 enum + 선택적 [reason] 을 묶은 값 타입으로 풍부화해, 행위(차단/색인/삭제 여부)는
 * 타입으로 노출하면서 [wire] 가 **기존 문자열을 정확히 보존**한다(마이그레이션·계약 변경 없음).
 *
 * 값 집합([Kind])과 `:` 사유 규칙은 코드의 실제 status 리터럴을 `grep` 으로 전수 도출했다.
 */
data class KnowledgeSourceStatus(
    val kind: Kind,
    val reason: String? = null,
) {
    /**
     * 지식 소스 status 의 고정 토큰 집합.
     *
     * - `blocked_*` 변종은 별도 값으로 둔다. 코드가 `status.startsWith("blocked")` 로 차단 여부를
     *   판정하던 것을 [isBlocked] 헬퍼로 대체한다.
     * - `REJECTED`/`DELETED` 만 사유([reason])를 가질 수 있다(와이어에서 `kind:reason`).
     */
    enum class Kind(
        val token: String,
    ) {
        PENDING("pending"),
        INDEXED("indexed"),
        REVIEW("review"),
        BLOCKED_SENSITIVE("blocked_sensitive"),
        BLOCKED_PROVIDER_SAFETY("blocked_provider_safety"),
        BLOCKED_TYPE("blocked_type"),
        BLOCKED_TOO_LARGE("blocked_too_large"),
        BLOCKED_BAD_URI("blocked_bad_uri"),
        BLOCKED_NON_HTTPS("blocked_non_https"),
        BLOCKED_SSRF("blocked_ssrf"),
        REJECTED("rejected"),
        DELETED("deleted"),
        ;

        /** `blocked_` 접두 차단 상태인가(기존 `startsWith("blocked")` 대체). */
        val isBlocked: Boolean get() = token.startsWith("blocked")

        companion object {
            private val BY_TOKEN: Map<String, Kind> = entries.associateBy { it.token }

            fun fromToken(token: String?): Kind? = token?.let { BY_TOKEN[it] }
        }
    }

    /** 차단 상태인가(기존 `status.startsWith("blocked")` 대체). */
    val isBlocked: Boolean get() = kind.isBlocked

    /** 색인 완료 상태인가(기존 `status == "indexed"`). */
    val isIndexed: Boolean get() = kind == Kind.INDEXED

    /** 색인 대기 상태인가(기존 `status == "pending"`). */
    val isPending: Boolean get() = kind == Kind.PENDING

    /** 삭제(tombstone) 상태인가(기존 `status.startsWith("deleted")`). */
    val isDeleted: Boolean get() = kind == Kind.DELETED

    /** 거절 상태인가(기존 `status.startsWith("rejected")`). */
    val isRejected: Boolean get() = kind == Kind.REJECTED

    /** 수동 검토 상태인가(기존 `status == "review"`). */
    val isReview: Boolean get() = kind == Kind.REVIEW

    /**
     * DB/JSON 와이어 문자열. 사유가 있으면 `kind:reason`, 없으면 토큰 그대로.
     * 기존 `rejected:${reason}` / `deleted:${reason}` 포맷을 정확히 재현한다.
     */
    val wire: String get() = if (reason != null) "${kind.token}:$reason" else kind.token

    /**
     * 이 상태에서 [next] 로 전이가 허용되는가(실제 코드 전이에서 도출한 가드).
     *
     * 도출 근거(`KnowledgeIngestionService`/`KnowledgeIndexingService`):
     * - addSource 초기값: PENDING / 각종 BLOCKED_* / REVIEW.
     * - approveSourceForIndexing: BLOCKED_* 또는 REVIEW → PENDING.
     * - markSourceIndexed / indexInlineSource: PENDING → INDEXED.
     * - parseSourceToDocument: (deleted 아님) → BLOCKED_SENSITIVE(민감 감지 시).
     * - rejectSource: any → REJECTED.
     * - removeSource: any → DELETED.
     *
     * DELETED 는 종단(삭제된 소스는 더 변경하지 않음). 그 외엔 거절/삭제로 항상 나갈 수 있고,
     * 차단/검토는 승인으로 PENDING 으로, PENDING 은 색인으로 INDEXED 로 갈 수 있다.
     */
    fun canTransitionTo(next: KnowledgeSourceStatus): Boolean {
        if (kind == Kind.DELETED) return next.kind == Kind.DELETED
        return when (next.kind) {
            Kind.DELETED, Kind.REJECTED -> true
            Kind.PENDING -> kind == Kind.PENDING || kind == Kind.REVIEW || kind.isBlocked
            Kind.INDEXED -> kind == Kind.PENDING
            Kind.BLOCKED_SENSITIVE -> true
            else -> kind == next.kind
        }
    }

    companion object {
        // 도메인적으로 의미 있는 생성 헬퍼(대입 지점 가독성).
        val PENDING = KnowledgeSourceStatus(Kind.PENDING)
        val INDEXED = KnowledgeSourceStatus(Kind.INDEXED)
        val BLOCKED_SENSITIVE = KnowledgeSourceStatus(Kind.BLOCKED_SENSITIVE)

        fun rejected(reason: String): KnowledgeSourceStatus = KnowledgeSourceStatus(Kind.REJECTED, reason)

        fun deleted(reason: String): KnowledgeSourceStatus = KnowledgeSourceStatus(Kind.DELETED, reason)

        /**
         * 와이어/DB 문자열 → 값 타입. `kind:reason` 을 분해하되, 사유를 갖지 않는 토큰의 `:` 이후는
         * 무시하지 않고 보존(라운드트립 안전). 알 수 없는/깨진 토큰은 견고하게 [Kind.PENDING] 로 폴백한다.
         */
        fun fromWire(value: String?): KnowledgeSourceStatus {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return PENDING
            val token = raw.substringBefore(':')
            val rest = if (raw.length > token.length) raw.substring(token.length + 1) else null
            // 알 수 없는 토큰은 PENDING 으로 폴백하되 `:` 이후 페이로드는 보존(라운드트립 안전).
            val kind = Kind.fromToken(token) ?: return KnowledgeSourceStatus(Kind.PENDING, rest)
            // 사유를 갖지 않는 토큰에 `:` 가 붙은 비정상 값도 바이트 보존을 위해 reason 으로 유지한다.
            return KnowledgeSourceStatus(kind, rest)
        }
    }
}
