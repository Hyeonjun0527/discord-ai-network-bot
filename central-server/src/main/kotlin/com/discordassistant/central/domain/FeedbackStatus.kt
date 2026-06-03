package com.discordassistant.central.domain

/**
 * AI 품질 피드백(`ai_feedback`) 검수 상태.
 *
 * 값 집합은 코드의 실제 status 리터럴에서 도출했다(`AiQualityFeedbackService`,
 * `MultiResponseService`):
 * - 제출 시 일반 피드백은 `open`, 신고성(feedbackType 에 "report" 포함)은 `needs_review`.
 * - 검수(`resolveFeedback` → `normalizeReviewStatus`) 결과: `resolved`/`dismissed`/`needs_review`.
 *
 * 와이어/DB 표현은 소문자([wire]) 로 유지해 기존 JSON 응답·DB VARCHAR·검수 큐 필터
 * (`findTop50ByGuildIdAndStatusOrderByCreatedAtDesc("needs_review")`)·`openReports` 카운트
 * (`status == needs_review`)와 동일하다. 스키마 마이그레이션은 불필요하다(VARCHAR 그대로).
 *
 * 전이맵([ALLOWED])은 코드가 실제로 수행하는 전이에서 도출했다:
 * - 생성: `OPEN` 또는 `NEEDS_REVIEW`.
 * - resolveFeedback: 임의 상태 → {`RESOLVED`, `DISMISSED`, `NEEDS_REVIEW`} (normalizeReviewStatus 가
 *   허용하는 결과 집합). reopen 경로로 `NEEDS_REVIEW` 재진입이 가능하므로 모든 상태에서 세 결과로
 *   전이할 수 있다(기존 동작 보존, 어떤 기존 전이도 거부하지 않는다).
 */
enum class FeedbackStatus(
    val wire: String,
) {
    /** 일반 품질 피드백(검수 대상 아님). */
    OPEN("open"),

    /** 신고로 접수되어 관리자 검수가 필요한 상태. */
    NEEDS_REVIEW("needs_review"),

    /** 검수자가 처리(해결) 완료. */
    RESOLVED("resolved"),

    /** 검수자가 기각(무시). */
    DISMISSED("dismissed"),
    ;

    /** 관리자 검수 큐에 노출되는(처리 대기) 상태. */
    val isOpenReport: Boolean get() = this == NEEDS_REVIEW

    /** 검수가 종료된 상태(해결/기각). */
    val isReviewed: Boolean get() = this == RESOLVED || this == DISMISSED

    /** 이 상태에서 [next] 로 전이가 허용되는가(resolveFeedback 가 허용하는 결과 집합). */
    fun canTransitionTo(next: FeedbackStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        private val REVIEW_TARGETS: Set<FeedbackStatus> = setOf(RESOLVED, DISMISSED, NEEDS_REVIEW)

        private val ALLOWED: Map<FeedbackStatus, Set<FeedbackStatus>> =
            mapOf(
                OPEN to REVIEW_TARGETS,
                NEEDS_REVIEW to REVIEW_TARGETS,
                RESOLVED to REVIEW_TARGETS,
                DISMISSED to REVIEW_TARGETS,
            )

        private val BY_WIRE: Map<String, FeedbackStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 알 수 없는/깨진 값은 견고하게 [OPEN] 으로 폴백(엔티티 기본값). */
        fun fromWire(value: String?): FeedbackStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: OPEN
    }
}
