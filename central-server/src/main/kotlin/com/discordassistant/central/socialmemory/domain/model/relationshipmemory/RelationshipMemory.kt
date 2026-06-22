package com.discordassistant.central.socialmemory.domain.model.relationshipmemory

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import java.time.Instant

/**
 * **특정 상호작용 사건과 그 관찰 결과**를 관계 상태와 분리해 담는 기억(NEXA-P07-T005, 순수 도메인 aggregate·불변).
 *
 * P06 의 [com.discordassistant.central.socialmemory.domain.model.relationship.MemberInteractionState] 가 집계된 관계
 * **상태**(빈도·reciprocity)라면, 이 aggregate 는 그 상태를 만든 **개별 사건의 관찰 기록**이다 — "언제 어떤 상호작용이
 * 있었고 무엇이 관찰됐다". 상태와 사건을 분리해 lineage·설명가능성을 보존한다(observable-state-policy #7).
 *
 * **acceptance(T005) — 성격 단정 대신 사건 기반 문장만 허용한다**: 관찰 결과는 닫힌 [ObservedRelationshipSignal] enum
 * 으로만 표현한다(예: REPLIED·REACTED·BANTER_RETURNED). "이 사람은 친절하다/내향적이다" 같은 성격·감정 단정은 타입상
 * 표현할 수 없다(자유 텍스트 라벨 필드 부재 — observable-state-policy 금지 추론). 관찰 신호만이다.
 *
 * 순수성: Spring/JPA/JDA·ainetwork 미참조. 표준 java.time 만 쓴다.
 */
data class RelationshipMemory(
    /** 이 관계 기억의 안정 식별자(가명·내부 ID). */
    val id: String,
    /** 이 기억이 묶인 guild 가명 + 가시성 스코프. */
    val visibility: VisibilityScope,
    /** NEXA 와 상호작용한 상대 가명 토큰(원본 snowflake 아님, guild-scoped). */
    val counterpartPseudonym: String,
    /** 이 상호작용 사건이 일어난 시점. */
    val occurredAt: Instant,
    /** 사건에서 **관찰된** 행동 신호(닫힌 집합 — 성격 단정 불가, acceptance T005). 비어 있을 수 없다. */
    val observedSignals: Set<ObservedRelationshipSignal>,
    /** 이 기억의 출처(원천 이벤트 ID·추출 버전·동의 스냅샷). */
    val source: MemorySource,
    /** 이 기억의 신뢰(출처 종류·반복·감쇠 반영, T010). */
    val confidence: Confidence,
    /** 이 사건 기억이 만료되는 시각(시간 유효성·decay, T012). null 이면 만료 미설정. */
    val expiresAt: Instant?,
    /** 생애 상태. 물리 삭제 대신 상태 전이로 현재 조회 제외. */
    val status: MemoryStatus = MemoryStatus.ACTIVE,
) {
    init {
        require(id.isNotBlank()) { "RelationshipMemory id 는 비어 있을 수 없다" }
        require(counterpartPseudonym.isNotBlank()) { "counterpartPseudonym 은 비어 있을 수 없다" }
        require(observedSignals.isNotEmpty()) { "관찰된 신호가 최소 1개여야 한다(사건 기반 기록)" }
        require(expiresAt == null || !expiresAt.isBefore(occurredAt)) { "expiresAt 은 occurredAt 이전일 수 없다" }
    }

    /** [now] 기준 만료됐는가. */
    fun isExpiredAt(now: Instant): Boolean = expiresAt != null && !now.isBefore(expiresAt)

    /** [now] 기준 retrieval 에 포함될 수 있는가(ACTIVE 이고 만료 전). */
    fun isRetrievableAt(now: Instant): Boolean = status.isRetrievable && !isExpiredAt(now)
}

/**
 * 관계 사건에서 **관찰된 행동 신호**의 닫힌 집합(NEXA-P07-T005). observable-state-policy 허용 목록(직접 관찰된 행동)만
 * — 기분·성격·감정 단정은 여기에 없다(금지 추론은 enum 으로도 만들지 않는다, 불변식 2).
 */
enum class ObservedRelationshipSignal {
    /** 상대가 NEXA 에게 응답했다(관찰 사실). */
    REPLIED,

    /** 상대가 NEXA 메시지에 reaction 을 달았다. */
    REACTED,

    /** 농담에 농담으로 반응했다(banter 수용 — 행동, 추론 아님). */
    BANTER_RETURNED,

    /** 상대가 NEXA 를 멘션·호출했다. */
    MENTIONED_NEXA,

    /** 상대가 응답하지 않았다(관찰된 무응답). */
    IGNORED,
}
