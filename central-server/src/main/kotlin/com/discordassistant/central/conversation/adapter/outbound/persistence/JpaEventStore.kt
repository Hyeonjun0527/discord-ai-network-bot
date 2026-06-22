package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.application.port.out.AppendResult
import com.discordassistant.central.conversation.application.port.out.EventStorePort
import com.discordassistant.central.conversation.application.port.out.StoredEventRecord
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * [EventStorePort] 의 JPA 구현 어댑터(NEXA-P03-T009/T010). 도메인 이벤트를 [NexaEventEntity] 로 매핑해
 * append-only 로 적재하고, projection 전달용 [NexaConversationOutboxEntity] 를 **같은 트랜잭션**에 기록한다(T011/T012).
 *
 * **멱등(acceptance T010)**: [append] 는 event_id 유니크 사전 검사로 중복 재수신을 흡수한다 — 같은 이벤트를 두 번
 * 넣어도 데이터가 중복되지 않고 [AppendResult] 로 신규/중복을 명시 구분해 돌려준다. 이벤트 행과 outbox 행이 같은
 * 트랜잭션에서 같이 생기므로 "event 만 있고 outbox 가 없는"(또는 반대) 상태가 생기지 않는다(원자성).
 *
 * **원문 비영속(data-categories.md 불변식 1)**: 이 어댑터는 도메인 이벤트의 원문 텍스트를 저장소로 끌어오지 않는다 —
 * 검색 가능한 메타데이터(채널/순서/시각/PII 등급)만 적재하고 content_cipher 는 null(미저장)로 둔다. 원문이 필요한
 * 영속은 별도 동의·암호화 경로를 거친다(이 포트의 책임 아님).
 */
@Repository
class JpaEventStore(
    private val events: NexaEventRepository,
    private val outbox: NexaConversationOutboxRepository,
    private val clock: Clock = Clock.systemUTC(),
) : EventStorePort {
    @Transactional
    override fun append(event: NormalizedDiscordEvent): AppendResult {
        val key = event.eventId.value
        if (events.existsByEventId(key)) {
            return AppendResult.DUPLICATE
        }
        events.save(
            NexaEventEntity(
                eventId = key,
                eventType = event::class.simpleName ?: "Unknown",
                guildId = event.guildId.value,
                channelId = event.channelId.value,
                sourceSequence = event.sourceSequence,
                occurredAt = event.occurredAt,
                receivedAt = event.receivedAt,
                privacyClass = event.privacyClass.name,
                sourceEventId = null,
                contentCipher = null,
                redacted = false,
            ),
        )
        if (!outbox.existsByEventId(key)) {
            outbox.save(
                NexaConversationOutboxEntity(
                    eventId = key,
                    channelId = event.channelId.value,
                    status = OutboxStatus.PENDING.name,
                    createdAt = clock.instant(),
                ),
            )
        }
        return AppendResult.APPENDED
    }

    @Transactional(readOnly = true)
    override fun exists(eventId: EventId): Boolean = events.existsByEventId(eventId.value)

    @Transactional(readOnly = true)
    override fun streamByChannel(channelId: ChannelId): List<StoredEventRecord> =
        events
            .findByChannelIdOrderBySourceSequenceAscOccurredAtAsc(channelId.value)
            .map { it.toRecord() }

    @Transactional(readOnly = true)
    override fun streamByRange(
        from: Instant,
        to: Instant,
    ): List<StoredEventRecord> =
        events
            .findByReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderBySourceSequenceAscOccurredAtAsc(from, to)
            .map { it.toRecord() }

    @Transactional
    override fun markRedacted(eventId: EventId): Boolean {
        val entity = events.findByEventId(eventId.value) ?: return false
        if (entity.redacted) return false
        entity.redacted = true
        entity.redactedAt = clock.instant()
        // 원문 참조 무효화(존재·순서·계보는 보존) — content_cipher 가 있었다면 폐기.
        entity.contentCipher = null
        events.save(entity)
        return true
    }

    private fun NexaEventEntity.toRecord(): StoredEventRecord =
        StoredEventRecord(
            eventId = EventId(eventId),
            channelId = ChannelId(channelId),
            occurredAt = occurredAt,
            receivedAt = receivedAt,
            sourceSequence = sourceSequence,
            redacted = redacted,
        )
}
