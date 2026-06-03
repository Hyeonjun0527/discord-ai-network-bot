package com.discordassistant.central.domain

/**
 * 다중응답 실행(`multi_response_run`) 상태머신 (#123 `PresetStatus` 와 동일 패턴).
 *
 * 기존에는 bare `String`(기본값 `"created"`) 이던 것을 도메인 enum 으로 풍부화한다. DB 컬럼과
 * 응답 JSON 은 **소문자 와이어 값**([wire]) 을 그대로 유지하므로 마이그레이션이나 외부 계약 변경이
 * 없다(동작 불변).
 *
 * 값 집합은 `MultiResponseService` 가 실제로 대입하는 status 리터럴에서 도출했다:
 * - 엔티티 기본값 `created`(아직 `MultiResponseRunEntity` 가 생성만 된 상태).
 * - `startRunEntity`/`startRuntimeObservation` 는 생성 직후 `planned` 로 저장한 뒤
 *   정책/안전/Provider 결과에 따라 `disabled_by_policy`·`blocked_sensitive`·`no_provider`·`running`
 *   중 하나로 전이한다.
 * - `synthesize`/`adoptCandidate` 는 `completed`, `failRun`/`completeBestEffort` 실패는 `failed`,
 *   `recordRuntimeSingleRouteResult` 단일경로 실패는 `no_provider`(기존 no_provider 유지) 또는 `failed`.
 *
 * 전이맵([ALLOWED])은 위 실제 전이에서 도출했으며, **기존 코드가 수행하는 전이는 전부 허용**한다
 * (가드가 기존 흐름을 거부하지 않는다). 동일 상태로의 재대입(idempotent)도 허용한다.
 */
enum class MultiResponseRunStatus(
    val wire: String,
) {
    /** 엔티티가 생성만 된 초기 상태(기본값). */
    CREATED("created"),

    /** Provider fan-out 계획 단계(후보 Provider 선정 직전/직후). */
    PLANNED("planned"),

    /** 후보 Provider 가 계획되어 실행/관측 중. */
    RUNNING("running"),

    /** 가용 Provider 가 없어 종료. */
    NO_PROVIDER("no_provider"),

    /** 민감정보처럼 보여 fan-out 을 차단하고 종료. */
    BLOCKED_SENSITIVE("blocked_sensitive"),

    /** 정책상 다중응답이 비활성화되어 종료. */
    DISABLED_BY_POLICY("disabled_by_policy"),

    /** 합성/채택으로 완료. */
    COMPLETED("completed"),

    /** 실패로 종료. */
    FAILED("failed"),
    ;

    /** 더 이상 나가는 전이가 없는 종단 상태(완료/실패/차단/비활성/Provider 없음). */
    val isTerminal: Boolean get() = this in TERMINAL

    /** 정상 완료 상태인가. */
    val isCompleted: Boolean get() = this == COMPLETED

    /** 이 상태에서 [next] 로 전이가 허용되는가(실제 코드 전이에서 도출한 가드). */
    fun canTransitionTo(next: MultiResponseRunStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        private val TERMINAL: Set<MultiResponseRunStatus> =
            setOf(NO_PROVIDER, BLOCKED_SENSITIVE, DISABLED_BY_POLICY, COMPLETED, FAILED)

        private val ALLOWED: Map<MultiResponseRunStatus, Set<MultiResponseRunStatus>> =
            mapOf(
                CREATED to setOf(PLANNED, RUNNING, NO_PROVIDER, BLOCKED_SENSITIVE, DISABLED_BY_POLICY, COMPLETED, FAILED),
                PLANNED to setOf(RUNNING, NO_PROVIDER, BLOCKED_SENSITIVE, DISABLED_BY_POLICY, COMPLETED, FAILED),
                RUNNING to setOf(COMPLETED, FAILED, NO_PROVIDER),
                NO_PROVIDER to emptySet(),
                BLOCKED_SENSITIVE to emptySet(),
                DISABLED_BY_POLICY to emptySet(),
                COMPLETED to emptySet(),
                FAILED to emptySet(),
            )

        private val BY_WIRE: Map<String, MultiResponseRunStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 대소문자 차이를 흡수하고, 알 수 없는/깨진 값은 [CREATED] 로 폴백. */
        fun fromWire(value: String?): MultiResponseRunStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: CREATED
    }
}
