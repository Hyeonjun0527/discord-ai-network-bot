package com.discordassistant.central.socialmemory.domain.model.episodic

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import java.time.Instant

/**
 * "언제 누구와 무슨 일이 있었다"는 **시점 있는 사건 기억**(NEXA-P07-T003, 순수 도메인 aggregate·불변).
 *
 * **acceptance(T003) — 원문 복사 대신 구조화 요약과 provenance 를 사용한다**: 사건 내용은 [summary] 라는 짧은 구조화
 * 요약(원문 메시지 전체가 아님)으로만 담고, 근거는 [source]([MemorySource]) 의 이벤트 ID 로 가리킨다. 원문은 event store
 * 에 있고 여기는 그 ID 만 운반한다(data-categories.md: 원문은 High·비영속). 생성자가 출처 없는 기억을 거부한다
 * ([MemorySource] init).
 *
 * 사건은 특정 시점([occurredAt]) 에 일어나고, 참여자 가명 scope([participants]) 와 가시성([visibility]), 그리고 만료
 * 시각([expiresAt]) 을 보존한다(시간 유효성·decay, taxonomy 불변식 4). 원문/심리 라벨이 아니라 관찰된 구조만 담는다.
 *
 * 순수성: Spring/JPA/JDA·ainetwork 미참조. 표준 java.time 만 쓴다.
 */
data class EpisodicMemory(
    /** 이 일화의 안정 식별자(가명·내부 ID). */
    val id: String,
    /** 이 일화가 묶인 guild 가명 + 가시성 스코프(cross-guild 노출 금지). */
    val visibility: VisibilityScope,
    /** 사건의 짧은 **구조화 요약**(원문 복사 아님 — acceptance T003). */
    val summary: String,
    /** 사건에 참여한 사람들의 guild-scoped 가명 토큰(원본 snowflake 아님). */
    val participants: Set<String>,
    /** 사건이 일어난 시점(시점 있는 일화의 기준). */
    val occurredAt: Instant,
    /** 이 기억의 출처(원천 이벤트 ID·추출 버전·동의 스냅샷). 출처 없는 기억은 만들 수 없다. */
    val source: MemorySource,
    /** 이 기억의 신뢰(출처 종류·반복·감쇠 반영, T010). */
    val confidence: Confidence,
    /** 이 일화가 만료되는 시각(시간 유효성·TTL, T012). null 이면 만료 미설정. */
    val expiresAt: Instant?,
    /** 생애 상태(ACTIVE/EXPIRED/INVALIDATED 등). 물리 삭제 대신 상태로 현재 조회 제외. */
    val status: MemoryStatus = MemoryStatus.ACTIVE,
) {
    init {
        require(id.isNotBlank()) { "EpisodicMemory id 는 비어 있을 수 없다" }
        require(summary.isNotBlank()) { "EpisodicMemory summary 는 비어 있을 수 없다(구조화 요약 필수)" }
        require(participants.none { it.isBlank() }) { "participant 가명은 비어 있을 수 없다" }
        require(expiresAt == null || !expiresAt.isBefore(occurredAt)) {
            "expiresAt 은 occurredAt 이전일 수 없다"
        }
    }

    /** [now] 기준 만료됐는가(만료 시각 도달). */
    fun isExpiredAt(now: Instant): Boolean = expiresAt != null && !now.isBefore(expiresAt)

    /** [now] 기준 retrieval 에 포함될 수 있는가(ACTIVE 이고 만료 전). */
    fun isRetrievableAt(now: Instant): Boolean = status.isRetrievable && !isExpiredAt(now)
}
