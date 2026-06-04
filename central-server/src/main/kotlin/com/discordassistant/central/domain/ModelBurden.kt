package com.discordassistant.central.domain

/**
 * 모델 부담 수준 (specs: `DM-V-ModelBurdenLevel`).
 *
 * 가격 등급이 아니라 프로바이더에게 주는 **처리 부담도**다.
 */
enum class ModelBurden(
    /**
     * 부담 순위(높을수록 큰 부담). 정렬·비교의 SSOT.
     * 이전엔 `AiNetworkDashboardQueryService.burdenRank` 가 동일 매핑을 손수 복제했다.
     */
    val rank: Int,
    /** Discord 슬래시 옵션 등 사용자 표시용 라벨(SSOT). */
    val label: String,
) {
    /** 작은 모델. 부담 낮음. 짧은 질문·간단 요약. */
    LIGHT(1, "LIGHT (가벼움)"),

    /** 일반 로컬 모델. 중간 부담. 코딩 질문·문서 요약. */
    STANDARD(2, "STANDARD (표준)"),

    /** 큰 모델. GPU/메모리 부담 큼. 긴 코드 분석·복잡 리뷰. */
    HEAVY(3, "HEAVY (무거움)"),

    /** 프로바이더가 특별히 제한(특정 역할·채널·관리자만). */
    RESTRICTED(4, "RESTRICTED (제한)"),
    ;

    companion object {
        /**
         * 역할별 허용 수준(`/llm-role-policy`)에서 관리자가 고를 수 있는 수준의 (라벨, 이름) choice — SSOT.
         * 시스템 전용인 [RESTRICTED] 는 제외(사용자가 직접 지정하지 않는다).
         */
        fun rolePolicyChoices(): List<Pair<String, String>> = listOf(LIGHT, STANDARD, HEAVY).map { it.label to it.name }

        /**
         * 부담 문자열(엔티티 raw String 컬럼·응답모드 별칭 포함) → enum.
         * `"DEEP"` 은 [HEAVY] 별칭. 인식 못 하는 값은 null.
         */
        fun fromName(value: String?): ModelBurden? =
            when (value?.trim()?.uppercase()) {
                "LIGHT" -> LIGHT
                "STANDARD" -> STANDARD
                "HEAVY", "DEEP" -> HEAVY
                "RESTRICTED" -> RESTRICTED
                else -> null
            }

        /** 부담 문자열의 순위. 기존 `burdenRank(value)` 보존(미상=0). */
        fun rankOf(value: String?): Int = fromName(value)?.rank ?: 0
    }
}

/**
 * 요청 무게 (specs: `DM-V-RequestWeight`). 필요 모델 부담 수준으로 매핑된다.
 */
enum class RequestWeight {
    LIGHT,
    MEDIUM,
    HEAVY,
    ;

    /** 요청 무게 → 필요한 최소 모델 부담 수준. */
    fun requiredBurden(): ModelBurden =
        when (this) {
            LIGHT -> ModelBurden.LIGHT
            MEDIUM -> ModelBurden.STANDARD
            HEAVY -> ModelBurden.HEAVY
        }
}
