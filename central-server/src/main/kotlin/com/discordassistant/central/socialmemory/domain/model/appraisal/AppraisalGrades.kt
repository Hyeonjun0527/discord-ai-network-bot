package com.discordassistant.central.socialmemory.domain.model.appraisal

/**
 * 사회 사건 종류 — core `social_appraiser.py`(B5) 이식. SSOT §9.2. 관계 축별 의미 분리의 기준.
 * GLM 은 이 등급만 출력한다(I2) — 숫자(관계 델타)는 절대 만들지 않는다(코드가 정한다).
 */
enum class SocialEventKind {
    /** 칭찬·감사. */
    PRAISE,

    /** 친근한 장난·놀림. */
    PLAYFUL,

    /** 비난·모욕. */
    INSULT,

    /** 사과·관계 수리. */
    APOLOGY,

    /** 협업·약속·도움. */
    COLLAB,

    /** 질문(도움 요청·일반 질문). */
    QUESTION,

    /** 잡담·일상. */
    SMALLTALK,
}

/** 강도 등급 — SSOT §12.2. Emotion/관계 GRADE 와 동일 서열. B6 가 충격량으로 매핑. */
enum class EventIntensity {
    /** 미세 — 일상적 호감·불편. */
    MICRO,

    /** 약함 — 명확하지만 가벼움. */
    MILD,

    /** 명확 — 구체적 감사·명확한 모욕·실제 협업. */
    CLEAR,

    /** 강함 — 드문 중대 사건(별도 검토). */
    STRONG,
}

/** 확신도 — SSOT §9.2·§12.3. LOW = 지속 상태 갱신 차단(보수). CLEAR = 분명한 근거. */
enum class AppraisalCertainty {
    /** 분명 — 근거가 충분하다. */
    CLEAR,

    /** 약함 — 억지 확정 금지(보수). 갱신 안 함. */
    LOW,
}
