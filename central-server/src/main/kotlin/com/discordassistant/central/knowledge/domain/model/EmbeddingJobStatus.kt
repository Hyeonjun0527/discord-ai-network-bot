package com.discordassistant.central.knowledge.domain.model

/**
 * 임베딩 색인 작업(`embedding_index_job`) 상태머신.
 *
 * 기존에는 bare `String`("queued"/"completed"/"failed"/"cancelled") 이던 것을 도메인 enum 으로
 * 풍부화한다. DB 컬럼과 응답 JSON 은 **소문자 와이어 값**([wire]) 을 그대로 유지하므로 마이그레이션이나
 * 외부 계약 변경이 없다(동작 불변).
 *
 * 값 집합은 코드의 실제 status 리터럴에서 전수 도출했다(`KnowledgeIndexingService`):
 * - 큐잉 시 `queued`.
 * - 완료 처리(`completeIndexJobSafely`)가 입력을 `completed`/`failed`/`cancelled` 로 정규화.
 *
 * 전이맵([ALLOWED]): `QUEUED → {COMPLETED, FAILED, CANCELLED}` 만 존재하고 완료 상태는 종단이다.
 */
enum class EmbeddingJobStatus(
    val wire: String,
    /** Discord 슬래시 옵션 등 사용자 표시용 한글 라벨(SSOT). */
    val label: String,
) {
    /** 큐에 등록되어 워커 처리 대기 중. */
    QUEUED("queued", "대기"),

    /** 색인 완료. */
    COMPLETED("completed", "완료"),

    /** 색인 실패. */
    FAILED("failed", "실패"),

    /** 색인 취소됨. */
    CANCELLED("cancelled", "취소"),
    ;

    /** 더 이상 나가는 전이가 없는 종단 상태인가. */
    val isTerminal: Boolean get() = this != QUEUED

    /** 성공적으로 완료된 상태인가. */
    val isSucceeded: Boolean get() = this == COMPLETED

    /** 이 상태에서 [next] 로 전이가 허용되는가(실제 코드 전이에서 도출한 가드). */
    fun canTransitionTo(next: EmbeddingJobStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        /**
         * `/ai-knowledge-job-complete` 에서 관리자가 기록하는 완료 결과의 (라벨, 와이어값) choice — SSOT.
         * 대기 상태([QUEUED])는 결과가 아니므로 제외한 종단 상태 셋이다.
         */
        fun completionChoices(): List<Pair<String, String>> = listOf(COMPLETED, FAILED, CANCELLED).map { it.label to it.wire }

        private val ALLOWED: Map<EmbeddingJobStatus, Set<EmbeddingJobStatus>> =
            mapOf(
                QUEUED to setOf(COMPLETED, FAILED, CANCELLED),
                COMPLETED to emptySet(),
                FAILED to emptySet(),
                CANCELLED to emptySet(),
            )

        private val BY_WIRE: Map<String, EmbeddingJobStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 알 수 없는/깨진 값은 견고하게 [QUEUED] 로 폴백. */
        fun fromWire(value: String?): EmbeddingJobStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: QUEUED
    }
}
