package com.discordassistant.central.conversation.application.port.out

import com.discordassistant.central.conversation.domain.model.event.EventId

/**
 * projection 적용 원장(ledger) 아웃바운드 포트(NEXA-P03-T016/T018). projection worker 가 한 [EventId] 를
 * 어느 projection version 으로 **이미 적용했는지** 기록하고, 영구 실패한 이벤트를 **dead-letter** 로 격리한다.
 *
 * **dedup(acceptance T016)**: [markApplied] 는 (eventId, projectionVersion) 조합이 처음일 때만 true 를
 * 돌려준다 — 같은 fixture 를 N 번 재생해도 projection input 으로 통과하는 건 1 회뿐이라, 중복 재생이
 * projection 상태를 두 번 바꾸지 않는다(멱등).
 *
 * **dead-letter(acceptance T018)**: 원문을 담지 않는다 — [EventId]·실패 사유 코드·시각만 기록한다. 운영자는
 * event ID 로만 조사하며 원문은 로그·dead-letter 에 절대 평문으로 남지 않는다(logging-boundary.md).
 *
 * 순수성: application.port 소속이라 도메인 타입([EventId])과 표준 타입만 본다(Spring/JPA 미참조 — 어댑터가 채운다).
 */
interface ProjectionLedgerPort {
    /**
     * ([eventId], [projectionVersion]) 적용을 기록한다. 처음이면 true(=projection 을 적용하라), 이미
     * 기록돼 있으면 false(=중복, 건너뛰라)를 돌려준다. dedup 의 핵심 — 멱등 소비를 보장한다.
     */
    fun markApplied(
        eventId: EventId,
        projectionVersion: Int,
    ): Boolean

    /** ([eventId], [projectionVersion]) 가 이미 적용됐는지(사전 검사). */
    fun isApplied(
        eventId: EventId,
        projectionVersion: Int,
    ): Boolean

    /**
     * 영구 실패한 이벤트를 dead-letter 로 격리한다. [reasonCode] 는 분류 코드(원문 아님), [detail] 은 원문/PII 가
     * 들어가면 안 되는 짧은 진단 문자열(예: 예외 클래스명). 같은 [eventId] 재격리는 멱등(덮어쓰기 또는 무시).
     */
    fun deadLetter(
        eventId: EventId,
        projectionVersion: Int,
        reasonCode: String,
        detail: String,
    )

    /** dead-letter 격리된 이벤트 메타 목록(원문 미포함; 운영 도구가 event ID 로 조사). 최신순. */
    fun deadLetters(limit: Int): List<DeadLetterRecord>

    /**
     * dead-letter 에서 [eventId] 를 꺼내 재처리 대기로 되돌린다(운영 재처리). 격리 해제만 하며 재적용은
     * 호출자(재처리 도구)가 한다. 대상이 없으면 false(멱등).
     */
    fun clearDeadLetter(eventId: EventId): Boolean
}

/**
 * dead-letter 레코드의 조사용 view(원문·PII 미포함). 운영자는 [eventId] 로만 원인을 추적한다(acceptance T018).
 */
data class DeadLetterRecord(
    val eventId: EventId,
    val projectionVersion: Int,
    val reasonCode: String,
    val detail: String,
    val failedAtEpochMs: Long,
)
