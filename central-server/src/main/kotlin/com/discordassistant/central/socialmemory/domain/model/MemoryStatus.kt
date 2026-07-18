package com.discordassistant.central.socialmemory.domain.model

/**
 * 기억 항목의 **생애 상태**(NEXA-P07-T008/T009/T012/T013 공용). 물리 삭제 대신 상태 전이로 "현재 조회 제외"를 표현한다
 * (supersession·conflict·invalidation·expiry 가 모두 원천 이벤트를 즉시 파기하지 않고 상태로 남긴다 — lineage 보존).
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
enum class MemoryStatus {
    /** 현재 유효한 기억. retrieval 에 포함된다. */
    ACTIVE,

    /** 미완 의도나 상호작용이 실제로 수행되어 정상 종결됨. */
    COMPLETED,

    /** 더 새로운 사실로 대체됨(T008). 물리 삭제하지 않고 현재 조회에서만 제외(validTo 설정). */
    SUPERSEDED,

    /** 동일 subject/predicate 의 상충 object 가 감지됨(T009). 근거 부족 시 한쪽을 임의 승격하지 않고 보류. */
    CONFLICTED,

    /** 출처가 모두/부분 redaction 되어 무효화됨(T013 삭제 cascade). retrieval 제외. */
    INVALIDATED,

    /** TTL 경과로 만료됨(T012). retrieval 제외, lineage 정책대로 처리. */
    EXPIRED,
    ;

    /** 현재 retrieval 에 포함될 수 있는 상태인가(ACTIVE 만). 나머지는 보존하되 현재 조회에서 제외. */
    val isRetrievable: Boolean
        get() = this == ACTIVE
}
