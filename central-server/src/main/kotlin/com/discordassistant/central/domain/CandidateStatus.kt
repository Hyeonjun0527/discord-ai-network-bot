package com.discordassistant.central.domain

/**
 * 다중응답 후보 답변(`candidate_answer`) 상태머신 (#123 `PresetStatus` 와 동일 패턴).
 *
 * 기존에는 bare `String`(기본값 `"pending"`) 이던 것을 도메인 enum 으로 풍부화한다. DB 컬럼과
 * 응답 JSON·`statusCounts` 집계는 **소문자 와이어 값**([wire]) 을 그대로 유지하므로 마이그레이션이나
 * 외부 계약 변경이 없다(동작 불변).
 *
 * 값 집합은 `MultiResponseService`/`MultiResponseReportingService` 의 실제 status 리터럴에서 도출했다:
 * - 엔티티 기본값 `pending`.
 * - fan-out 계획 시 `planned`(`startRunEntity`/`startRuntimeObservation`).
 * - 결과 기록 시 `completed`/`failed`(`recordRuntimeSingleRouteResult`), 또는 컨트롤러 wire 로 들어오는
 *   `recordCandidate` 의 `completed`/`failed`/`timeout`(리포팅이 `timeout`/`rejected` 도 구분 집계).
 *
 * `recordCandidate` 는 컨트롤러에서 자유 문자열 status 를 받으므로(기본값 `"completed"`), 리포팅의
 * `statusCounts`·`failureSummary` 는 입력 와이어 문자열을 정확히 보존해야 한다. 따라서 enum 은 코드가
 * 실제로 비교/대입하는 와이어 집합을 모두 포함하고, [fromWire] 는 기존 견고 파싱(소문자 흡수 + 폴백)을
 * 그대로 따른다.
 *
 * 전이맵([ALLOWED])은 위 실제 전이에서 도출했으며, **기존 코드가 수행하는 전이는 전부 허용**한다
 * (가드가 기존 흐름을 거부하지 않는다). 동일 상태로의 재대입(idempotent)도 허용한다.
 */
enum class CandidateStatus(
    val wire: String,
) {
    /** 초기 상태(기본값). */
    PENDING("pending"),

    /** fan-out 으로 Provider 에 계획됨. */
    PLANNED("planned"),

    /** 답변 완료(answerRef 존재). */
    COMPLETED("completed"),

    /** 실패. */
    FAILED("failed"),

    /** 타임아웃(리포팅에서 별도 집계). */
    TIMEOUT("timeout"),

    /** 거절(리포팅에서 실패로 합산). */
    REJECTED("rejected"),
    ;

    /** 더 이상 나가는 전이가 없는 종단 상태(완료/실패/타임아웃/거절). */
    val isTerminal: Boolean get() = this in TERMINAL

    /** 정상 완료 상태인가. */
    val isCompleted: Boolean get() = this == COMPLETED

    /** 이 상태에서 [next] 로 전이가 허용되는가(실제 코드 전이에서 도출한 가드). */
    fun canTransitionTo(next: CandidateStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        private val TERMINAL: Set<CandidateStatus> = setOf(COMPLETED, FAILED, TIMEOUT, REJECTED)

        private val ALLOWED: Map<CandidateStatus, Set<CandidateStatus>> =
            mapOf(
                PENDING to setOf(PLANNED, COMPLETED, FAILED, TIMEOUT, REJECTED),
                PLANNED to setOf(COMPLETED, FAILED, TIMEOUT, REJECTED),
                COMPLETED to emptySet(),
                FAILED to emptySet(),
                TIMEOUT to emptySet(),
                REJECTED to emptySet(),
            )

        private val BY_WIRE: Map<String, CandidateStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 대소문자 차이를 흡수하고, 알 수 없는/깨진 값은 [PENDING] 으로 폴백. */
        fun fromWire(value: String?): CandidateStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: PENDING
    }
}
