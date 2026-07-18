package com.discordassistant.central.socialmemory.domain.model.intent

import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import java.time.Instant

/**
 * "나중에 하기로 한" **미완 의도**(NEXA-P07-T006, 순수 도메인 aggregate·불변). 예: "보라에게 자료 찾아주기로 함".
 *
 * **acceptance(T006) — 자연어 chain-of-thought 를 저장하지 않고 구조화 필드만 가진다**: 의도는 짧은 [topic],
 * 대상 가명([targetPseudonym]), 닫힌 [socialAct] enum, [activation]/[urgency] 닫힌 enum, 출처, 만료로만 표현한다 —
 * 모델의 추론 과정(자유 텍스트 사고 사슬) 필드가 없다(data-categories.md: 원문·프롬프트·응답 저장 금지).
 *
 * 의도는 만료([expiresAt]) 되거나 해결([MemoryStatus.SUPERSEDED] 등)되며 영구 보류로 남지 않는다(시간 유효성).
 *
 * 순수성: Spring/JPA/JDA·ainetwork 미참조. 표준 java.time 만 쓴다.
 */
data class PendingIntent(
    /** 이 의도의 안정 식별자(가명·내부 ID). */
    val id: String,
    /** 이 의도가 묶인 guild 가명 + 가시성 스코프. */
    val visibility: VisibilityScope,
    /** 무엇에 대한 의도인지 짧은 구조화 topic(원문/사고 사슬 아님). */
    val topic: String,
    /** 의도 대상 가명 토큰(원본 snowflake 아님, guild-scoped). null 이면 특정 대상 없음. */
    val targetPseudonym: String?,
    /** 하기로 한 사회적 행위(닫힌 집합). 자유 텍스트가 아님. */
    val socialAct: SocialAct,
    /** 의도가 발동될 조건/상태(닫힌 집합). */
    val activation: IntentActivation,
    /** 긴급도(닫힌 집합). */
    val urgency: IntentUrgency,
    /** 이 의도의 출처(원천 이벤트 ID·추출 버전·동의 스냅샷). */
    val source: MemorySource,
    /** 이 의도가 만료되는 시각(미완 의도의 시간 유효성·만료, T012). null 이면 만료 미설정. */
    val expiresAt: Instant?,
    /** 추출된 약속이 실제 약속일 가능성. 운영 로그로 보정되기 전에는 정책 신호일 뿐 확률로 간주하지 않는다. */
    val confidence: Double = 1.0,
    /** 생애 상태(ACTIVE=미완, SUPERSEDED=해결됨, EXPIRED 등). 물리 삭제 대신 상태 전이. */
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    /** 이 의도가 귀속된 canonical 대화 focus. null은 기존 데이터 호환용이다. */
    val focusThreadKey: String? = null,
    /** 수행 완료 시각. ACTIVE이면 null이다. */
    val completedAt: Instant? = null,
    /** 완료를 증명한 실제 실행 action ID. 판단 모델의 선언만으로는 채울 수 없다. */
    val completedByActionId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "PendingIntent id 는 비어 있을 수 없다" }
        require(topic.isNotBlank()) { "topic 은 비어 있을 수 없다" }
        require(targetPseudonym == null || targetPseudonym.isNotBlank()) { "targetPseudonym 은 빈 문자열일 수 없다" }
        require(focusThreadKey == null || focusThreadKey.isNotBlank()) { "focusThreadKey 는 빈 문자열일 수 없다" }
        require(confidence in 0.0..1.0) { "confidence 는 0.0 이상 1.0 이하여야 한다" }
        require(completedByActionId == null || completedByActionId.isNotBlank()) { "completedByActionId 는 빈 문자열일 수 없다" }
        require((status == MemoryStatus.COMPLETED) == (completedAt != null && completedByActionId != null)) {
            "COMPLETED 상태, completedAt, completedByActionId는 함께 있어야 한다"
        }
    }

    /** [now] 기준 만료됐는가. */
    fun isExpiredAt(now: Instant): Boolean = expiresAt != null && !now.isBefore(expiresAt)

    /** [now] 기준 아직 처리 대상으로 살아 있는가(ACTIVE 이고 만료 전). */
    fun isActiveAt(now: Instant): Boolean = status.isRetrievable && !isExpiredAt(now)

    fun complete(
        at: Instant,
        actionId: String,
    ): PendingIntent = copy(status = MemoryStatus.COMPLETED, completedAt = at, completedByActionId = actionId)
}

/** 하기로 한 사회적 행위의 닫힌 집합(NEXA-P07-T006). 자유 텍스트 사고 사슬 대신 구조화 act. */
enum class SocialAct {
    /** 답장/회신하기로 함. */
    REPLY,

    /** 자료·정보 찾아주기로 함. */
    FIND_INFORMATION,

    /** 나중에 다시 확인·리마인드하기로 함. */
    FOLLOW_UP,

    /** 소개·연결해 주기로 함. */
    INTRODUCE,

    /** 사과/정정하기로 함. */
    APOLOGIZE,

    /** 약속한 이야기를 실제로 수행. */
    TELL_STORY,

    /** 약속한 설명을 실제로 수행. */
    EXPLAIN,

    /** 약속한 답변을 실제로 수행. */
    ANSWER,
}

/** 의도 발동 조건(닫힌 집합). */
enum class IntentActivation {
    /** 다음에 그 대상이 나타나면. */
    WHEN_TARGET_RETURNS,

    /** 특정 시점 이후. */
    AFTER_TIME,

    /** 즉시 처리 대상. */
    IMMEDIATE,
}

/** 의도 긴급도(닫힌 집합). */
enum class IntentUrgency {
    LOW,
    NORMAL,
    HIGH,
}
