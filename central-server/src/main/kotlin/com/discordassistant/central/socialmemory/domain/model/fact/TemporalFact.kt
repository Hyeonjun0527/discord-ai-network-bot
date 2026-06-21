package com.discordassistant.central.socialmemory.domain.model.fact

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import java.time.Instant

/**
 * 관찰/언급된 **안정 속성 사실**(NEXA-P07-T004, 순수 도메인 aggregate·불변). 예: "지현은 파이썬을 쓴다(고 말했다)".
 *
 * subject/predicate/object 삼항([subject]/[predicate]/[obj]) 으로 구조화하고, **유효 구간**([validFrom]~[validTo]) 으로
 * 시간성을 가진다. object 는 짧은 구조화 값이지 원문 복사가 아니다(data-categories.md). 출처([source]) 와 신뢰([confidence])
 * 를 보존한다.
 *
 * **acceptance(T004) — validTo 가 없는 현재 사실과 종료된 과거 사실을 동시에 보존한다**: [validTo] 가 null 이면 "지금도
 * 유효한 현재 사실", 값이 있으면 "그 시점에 종료된 과거 사실"이다. supersession(T008) 은 과거 사실을 물리 삭제하지 않고
 * [validTo] 를 채워 [SUPERSEDED] 로 둔다 — 같은 subject/predicate 의 현재 사실과 과거 사실이 한 저장소에 공존한다.
 *
 * 순수성: Spring/JPA/JDA·ainetwork 미참조. 표준 java.time 만 쓴다.
 */
data class TemporalFact(
    /** 이 사실의 안정 식별자(가명·내부 ID). */
    val id: String,
    /** 이 사실이 묶인 guild 가명 + 가시성 스코프. */
    val visibility: VisibilityScope,
    /** 사실의 주어(보통 사람 가명 토큰). */
    val subject: String,
    /** 사실의 술어(닫힌/안정 키, 예: uses_language·plays_game). 원문 문장이 아님. */
    val predicate: String,
    /** 사실의 목적어(짧은 구조화 값, 원문 복사 아님). */
    val obj: String,
    /** 이 사실이 유효해지기 시작한 시각. */
    val validFrom: Instant,
    /** 이 사실이 종료된 시각. null 이면 **현재도 유효한 사실**(acceptance T004). */
    val validTo: Instant?,
    /** 이 사실의 출처(원천 이벤트 ID·추출 버전·동의 스냅샷). */
    val source: MemorySource,
    /** 이 사실의 신뢰(출처 종류·반복·감쇠 반영, T010). */
    val confidence: Confidence,
    /** 생애 상태(ACTIVE/SUPERSEDED/CONFLICTED/INVALIDATED/EXPIRED). 물리 삭제 대신 상태 전이. */
    val status: MemoryStatus = MemoryStatus.ACTIVE,
) {
    init {
        require(id.isNotBlank()) { "TemporalFact id 는 비어 있을 수 없다" }
        require(subject.isNotBlank()) { "subject 는 비어 있을 수 없다" }
        require(predicate.isNotBlank()) { "predicate 는 비어 있을 수 없다" }
        require(obj.isNotBlank()) { "obj 는 비어 있을 수 없다" }
        require(validTo == null || !validTo.isBefore(validFrom)) { "validTo 는 validFrom 이전일 수 없다" }
    }

    /** validTo 가 없으면 현재(열린 구간) 사실이다 — acceptance T004 의 "현재 사실". */
    val isCurrent: Boolean
        get() = validTo == null

    /** 같은 사실의 다른 진술인가(같은 subject+predicate). conflict/supersession 판정의 그룹 키(T008/T009). */
    fun sameClaimAs(other: TemporalFact): Boolean =
        subject == other.subject &&
            predicate == other.predicate &&
            visibility.guildPseudonym == other.visibility.guildPseudonym

    /** [now] 기준 유효 구간 안인가(validFrom 이후, validTo 미설정이거나 이전). */
    fun isValidAt(now: Instant): Boolean = !now.isBefore(validFrom) && (validTo == null || now.isBefore(validTo))

    /** [now] 기준 retrieval 에 포함될 수 있는가(ACTIVE 이고 유효 구간 안). */
    fun isRetrievableAt(now: Instant): Boolean = status.isRetrievable && isValidAt(now)
}
