package com.discordassistant.central.domain

/**
 * 모델 부담 수준 (specs: `DM-V-ModelBurdenLevel`).
 *
 * 가격 등급이 아니라 프로바이더에게 주는 **처리 부담도**다.
 */
enum class ModelBurden {
    /** 작은 모델. 부담 낮음. 짧은 질문·간단 요약. */
    LIGHT,

    /** 일반 로컬 모델. 중간 부담. 코딩 질문·문서 요약. */
    STANDARD,

    /** 큰 모델. GPU/메모리 부담 큼. 긴 코드 분석·복잡 리뷰. */
    HEAVY,

    /** 프로바이더가 특별히 제한(특정 역할·채널·관리자만). */
    RESTRICTED,
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
