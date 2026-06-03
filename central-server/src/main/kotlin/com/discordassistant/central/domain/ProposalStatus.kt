package com.discordassistant.central.domain

/**
 * AI 설정 변경 제안(`ai_change_proposal`) 상태머신.
 *
 * 기존에는 bare `String`("pending"/"approved"/"rejected"/"stale") 이던 것을 도메인 enum 으로 풍부화한다.
 * DB 컬럼과 응답 JSON 은 **소문자 와이어 값**([wire]) 을 그대로 유지하므로 마이그레이션이나 외부 계약 변경이
 * 없다(동작 불변).
 *
 * 전이맵([ALLOWED])은 코드가 실제로 수행하는 전이에서 도출했다(`ChannelAiCustomizationService`):
 * - 생성 시 `PENDING`(승인 필요) 또는 `APPROVED`(자동 게시).
 * - `approveProposal`: `PENDING` 만 허용 → payload 변조 감지 시 `STALE`, 정상 승인 시 `APPROVED`.
 * - `rejectProposal`: `PENDING` 만 허용 → `REJECTED`.
 *
 * 따라서 실제 전이는 `PENDING → {APPROVED, REJECTED, STALE}` 뿐이고, 그 외 상태에서 나가는 전이는
 * 코드에 존재하지 않으므로 [APPROVED]/[REJECTED]/[STALE] 은 종단 상태다.
 */
enum class ProposalStatus(
    val wire: String,
) {
    /** 승인 대기(검토 필요). */
    PENDING("pending"),

    /** 승인되어 채널 AI 에 적용됨. */
    APPROVED("approved"),

    /** 거절됨. */
    REJECTED("rejected"),

    /** 검토 요청 이후 payload 가 바뀌어 무효화됨. */
    STALE("stale"),
    ;

    /** 더 이상 나가는 전이가 없는 종단 상태인가. */
    val isTerminal: Boolean get() = this != PENDING

    /** 이 상태에서 [next] 로 전이가 허용되는가(실제 코드 전이에서 도출한 가드). */
    fun canTransitionTo(next: ProposalStatus): Boolean = next in ALLOWED[this].orEmpty()

    companion object {
        private val ALLOWED: Map<ProposalStatus, Set<ProposalStatus>> =
            mapOf(
                PENDING to setOf(APPROVED, REJECTED, STALE),
                APPROVED to emptySet(),
                REJECTED to emptySet(),
                STALE to emptySet(),
            )

        private val BY_WIRE: Map<String, ProposalStatus> = entries.associateBy { it.wire }

        /**
         * 소문자 와이어 문자열 → enum(견고 파싱). 대소문자 차이를 흡수하고, 알 수 없는/깨진 값은
         * [PENDING] 으로 폴백한다(기존 load 견고성 보존).
         */
        fun fromWire(value: String?): ProposalStatus = value?.let { BY_WIRE[it.lowercase()] } ?: PENDING
    }
}
