package com.discordassistant.central.conversation.application.port.out

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import java.time.Instant

/**
 * conversation 정규화 이벤트 저장소의 아웃바운드 포트(NEXA-P03-T008, 헥사고날).
 *
 * conversation 수집 경계가 동의·정규화를 통과한 [NormalizedDiscordEvent] 를 **append-only** 로 적재하고,
 * projection/삭제 전파가 그 스트림을 읽는 단일 포트다. 구현 어댑터(JPA)는 adapter.outbound.persistence 에 둔다.
 *
 * **append-only 불변식(acceptance T008)**: 이 포트는 update/delete 메서드를 노출하지 않는다. 이벤트는 한 번
 * 적재되면 내용이 갱신·삭제되지 않는다(event-sourcing 원칙). 삭제 전파(deletion-propagation.md)는 행 삭제가
 * 아니라 [markRedacted] 로 **redaction 상태 전이**만 한다 — 이벤트의 존재·순서·계보는 보존하되 원문 참조만 무효화.
 *
 * **멱등성(T010/T011)**: [append] 는 [EventId] 유니크로 중복 재수신을 흡수한다 — 같은 이벤트를 두 번 넣어도
 * 데이터가 중복되지 않고 명시적 결과([AppendResult])로 신규/중복을 구분해 돌려준다.
 *
 * 순수성 경계: 포트 계약은 application 레이어 소속이라 도메인 타입([NormalizedDiscordEvent]/[EventId]/[ChannelId])과
 * 표준 [Instant] 만 본다 — Spring/JPA/JDA 타입을 참조하지 않는다(어댑터가 채운다).
 *
 * 근거: conversation-context.md(conversation=관찰, 정규화 이벤트 소유), data-categories.md(High 원문 기본 비영속),
 * data-lineage.md(source_event_id 역추적), deletion-propagation.md(redaction 전파).
 */
interface EventStorePort {
    /**
     * 정규화 [event] 를 append-only 로 적재한다. [EventId] 가 이미 존재하면 **재삽입하지 않고**
     * [AppendResult.DUPLICATE] 를 돌려준다(멱등 — at-least-once 재수신 안전). 신규면 [AppendResult.APPENDED].
     */
    fun append(event: NormalizedDiscordEvent): AppendResult

    /** 주어진 [eventId] 의 이벤트가 이미 적재되어 있는지(dedup 사전 검사용). */
    fun exists(eventId: EventId): Boolean

    /**
     * 한 채널의 이벤트를 결정론적 순서(sourceSequence → occurredAt)로 스트리밍한다(채널 순서 보장).
     * redaction 된 이벤트도 존재·순서는 유지되므로 포함되며, 호출자가 상태로 구분한다.
     */
    fun streamByChannel(channelId: ChannelId): List<StoredEventRecord>

    /**
     * [from, to) 수신 시각 범위의 이벤트를 결정론적 순서로 스트리밍한다(보존/삭제 배치·재처리용).
     * 범위는 [StoredEventRecord.receivedAt] 기준이며 from 포함·to 미포함이다.
     */
    fun streamByRange(
        from: Instant,
        to: Instant,
    ): List<StoredEventRecord>

    /**
     * [eventId] 이벤트를 **redaction 상태로 전이**한다(삭제 전파). 행을 지우지 않고 원문 참조만 무효화해
     * 존재·순서·계보는 보존한다(deletion-propagation.md). 대상이 없으면 false(멱등 — 이미 없거나 처리됨).
     */
    fun markRedacted(eventId: EventId): Boolean
}

/** [EventStorePort.append] 결과 — 신규 적재인지 중복 흡수인지 호출자가 멱등하게 분기하도록 명시 구분한다. */
enum class AppendResult {
    /** 처음 보는 [EventId] — 새 행으로 적재됐다. */
    APPENDED,

    /** 이미 적재된 [EventId] — 재삽입하지 않았다(멱등 흡수, 데이터 중복 없음). */
    DUPLICATE,
}

/**
 * 저장소에서 읽은 이벤트 레코드의 최소 메타데이터 view(검색 가능 메타만; 원문 평문 미포함).
 *
 * data-categories.md(High 원문 기본 비영속)에 따라 원문 텍스트는 이 view 에 평문으로 담기지 않는다 —
 * 적재 시점에 redaction 됐는지([redacted])와 순서/계보 키만 노출한다. 원문이 필요한 소비자는 별도 동의·복호
 * 경로를 거친다(이 포트의 책임 아님).
 */
data class StoredEventRecord(
    val eventId: EventId,
    val channelId: ChannelId,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val sourceSequence: Long,
    /** redaction(삭제 전파) 상태 — true 면 원문 참조가 무효화됐다(존재·순서는 보존). */
    val redacted: Boolean,
)
