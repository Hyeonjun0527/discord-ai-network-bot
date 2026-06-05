package com.discordassistant.central.multiresponse.domain.model

/**
 * 다중응답 합성 결과(`synthesis_result`) 상태머신 (#123 `PresetStatus` 와 동일 패턴).
 *
 * 기존에는 bare `String`(기본값 `"pending"`) 이던 것을 도메인 enum 으로 풍부화한다. DB 컬럼과
 * 응답 JSON 은 **소문자 와이어 값**([wire]) 을 그대로 유지하므로 마이그레이션이나 외부 계약 변경이
 * 없다(동작 불변).
 *
 * 값 집합은 `MultiResponseService` 의 실제 status 리터럴에서 도출했다:
 * - 엔티티 기본값 `pending`(합성 결과 레코드 생성 직후).
 * - `synthesize`/`adoptCandidate` 가 합성/채택을 마치면 `completed` 로 전이한다.
 *
 * 실제 전이는 `PENDING → COMPLETED` 뿐이고 그 외 나가는 전이는 코드에 없으므로 [COMPLETED] 는
 * 종단 상태다. **기존 코드가 수행하는 전이는 전부 허용**하며 동일 상태 재대입(idempotent)도 허용한다.
 */
enum class SynthesisStatus(
    val wire: String,
) {
    /** 초기 상태(기본값). */
    PENDING("pending"),

    /** 합성/채택 완료. */
    COMPLETED("completed"),
    ;

    /** 더 이상 나가는 전이가 없는 종단 상태. */
    val isTerminal: Boolean get() = this == COMPLETED

    /** 정상 완료 상태인가. */
    val isCompleted: Boolean get() = this == COMPLETED

    /** 이 상태에서 [next] 로 전이가 허용되는가(실제 코드 전이에서 도출한 가드). */
    fun canTransitionTo(next: SynthesisStatus): Boolean = next == this || next in ALLOWED[this].orEmpty()

    companion object {
        private val ALLOWED: Map<SynthesisStatus, Set<SynthesisStatus>> =
            mapOf(
                PENDING to setOf(COMPLETED),
                COMPLETED to emptySet(),
            )

        private val BY_WIRE: Map<String, SynthesisStatus> = entries.associateBy { it.wire }

        /** 소문자 와이어/DB 문자열 → enum. 대소문자 차이를 흡수하고, 알 수 없는/깨진 값은 [PENDING] 으로 폴백. */
        fun fromWire(value: String?): SynthesisStatus = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: PENDING
    }
}
