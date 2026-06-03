package com.discordassistant.central.domain

/**
 * 공개 프리셋 신고(`preset_report`) 검수 상태.
 *
 * 값 집합은 코드의 실제 status 리터럴에서 도출했다(`PresetRegistryService`,
 * `PresetCatalogQueryService`):
 * - 생성 시 기본 `open`(reportPreset 은 status 를 지정하지 않아 엔티티 기본값 `open`).
 * - `reviewReport(decision)`: decision 을 소문자 정규화한 뒤 매핑한다 —
 *   `dismissed`→`dismiss`, `removed`→`remove`, `suspended`→`suspend`, 그 외(빈 값 포함)는
 *   `ifBlank { "reviewed" }` 로 정규화된 동사형을 그대로 저장한다.
 *
 * 따라서 코드가 실제로 저장하는 캐노니컬 와이어 값 집합은
 * `open`/`dismiss`/`suspend`/`remove`/`reviewed` 다(Discord 슬래시 옵션은 dismiss/suspend/remove
 * 셋으로 제한되고, REST `decision` 의 비-캐노니컬 입력은 빈 값일 때 `reviewed` 로 귀결된다).
 * 와이어/DB 표현은 소문자([wire]) 로 유지해 기존 JSON 응답·DB VARCHAR·`findByStatus`·신고 큐
 * 카운트(`status == open`, `reportStatusCounts` 키)와 동일하다. 마이그레이션 불필요.
 *
 * 전이맵([ALLOWED])은 `reviewReport` 의 실제 전이에서 도출했다:
 * - 생성: `OPEN`.
 * - reviewReport: `OPEN`(또는 임의 상태) → {`DISMISS`, `SUSPEND`, `REMOVE`, `REVIEWED`}.
 *   reviewReport 는 현재 상태를 검사하지 않으므로 모든 상태에서 네 결정으로 전이할 수 있다
 *   (기존 동작 보존, 어떤 기존 전이도 거부하지 않는다).
 */
enum class PresetReportStatus(
    val wire: String,
) {
    /** 접수되어 검수 대기 중인 신고. */
    OPEN("open"),

    /** 검수자가 기각함(reviewReport decision=dismiss). */
    DISMISS("dismiss"),

    /** 검수자가 대상 프리셋을 일시 중단함(decision=suspend). */
    SUSPEND("suspend"),

    /** 검수자가 대상 프리셋을 제거함(decision=remove). */
    REMOVE("remove"),

    /** 그 외 검수 처리(비-캐노니컬/빈 decision 의 기본 귀결). */
    REVIEWED("reviewed"),
    ;

    /** 아직 처리되지 않은(큐에 노출되는) 신고. */
    val isOpen: Boolean get() = this == OPEN

    /** 이 상태에서 [next] 로 전이가 허용되는가(reviewReport 가 허용하는 결정 집합). */
    fun canTransitionTo(next: PresetReportStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        private val REVIEW_DECISIONS: Set<PresetReportStatus> = setOf(DISMISS, SUSPEND, REMOVE, REVIEWED)

        private val ALLOWED: Map<PresetReportStatus, Set<PresetReportStatus>> =
            entries.associateWith { REVIEW_DECISIONS }

        private val BY_WIRE: Map<String, PresetReportStatus> = entries.associateBy { it.wire }

        /**
         * `reviewReport` 의 decision 정규화 결과(소문자 동사형) → enum.
         *
         * 기존 매핑을 그대로 보존한다: `dismissed`→DISMISS, `removed`→REMOVE, `suspended`→SUSPEND,
         * 동사형(`dismiss`/`remove`/`suspend`)은 동일 매핑, 빈 값/그 외는 [REVIEWED].
         */
        fun fromDecision(decision: String?): PresetReportStatus =
            when (decision?.trim()?.lowercase()?.ifBlank { null }) {
                "dismiss", "dismissed" -> DISMISS
                "remove", "removed" -> REMOVE
                "suspend", "suspended" -> SUSPEND
                null -> REVIEWED
                else -> REVIEWED
            }

        /** 소문자 와이어/DB 문자열 → enum. 알 수 없는/깨진 값은 견고하게 [OPEN] 으로 폴백(엔티티 기본값). */
        fun fromWire(value: String?): PresetReportStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: OPEN
    }
}
