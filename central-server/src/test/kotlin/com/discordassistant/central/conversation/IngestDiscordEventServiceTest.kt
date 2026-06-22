package com.discordassistant.central.conversation

import com.discordassistant.central.conversation.application.ingest.ConsentSnapshot
import com.discordassistant.central.conversation.application.ingest.IngestDiscordEventService
import com.discordassistant.central.conversation.application.ingest.IngestEnvelope
import com.discordassistant.central.conversation.application.ingest.IngestOutcome
import com.discordassistant.central.conversation.application.ingest.MapperVersion
import com.discordassistant.central.conversation.application.ingest.ShardId
import com.discordassistant.central.conversation.application.port.out.AppendResult
import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.conversation.application.port.out.EventStorePort
import com.discordassistant.central.conversation.application.port.out.StoredEventRecord
import com.discordassistant.central.conversation.domain.model.ConsentDecision
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageCreated
import com.discordassistant.central.conversation.domain.model.event.MessageId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P03-T011 이벤트 append 유스케이스 단위 테스트(순수, fake 포트).
 *
 * acceptance:
 * - 동의 미허용 시 적재하지 않는다(REJECTED_CONSENT, fail-closed).
 * - 동의 허용 시 append(+outbox)가 한 경계로 일어난다 — fake store 는 event 와 outbox 를 같이 기록한다.
 * - 중복 재수신은 DUPLICATE(멱등 — outbox 중복 side effect 없음).
 * - "outbox 없이 event 만" 또는 반대 상태가 생기지 않는다(원자성).
 */
class IngestDiscordEventServiceTest {
    private val guildId = 1L
    private val userId = 42L
    private val channelId = 100L
    private val secret = "원문-PII-leak-canary"

    /** event 와 outbox 를 항상 같이 기록하는 fake transactional store(원자성 모사). */
    private class FakeEventStore : EventStorePort {
        val eventIds = mutableListOf<String>()
        val outboxIds = mutableListOf<String>()

        override fun append(event: NormalizedDiscordEvent): AppendResult {
            val key = event.eventId.value
            if (key in eventIds) return AppendResult.DUPLICATE
            // 원자성: event 와 outbox 를 같이 기록(부분 상태 없음).
            eventIds += key
            outboxIds += key
            return AppendResult.APPENDED
        }

        override fun exists(eventId: EventId) = eventId.value in eventIds

        override fun streamByChannel(channelId: ChannelId): List<StoredEventRecord> = emptyList()

        override fun streamByRange(
            from: Instant,
            to: Instant,
        ): List<StoredEventRecord> = emptyList()

        override fun markRedacted(eventId: EventId) = false
    }

    private fun message(id: String): MessageCreated =
        MessageCreated(
            eventId = EventId(id),
            guildId = GuildId(guildId),
            channelId = ChannelId(channelId),
            occurredAt = Instant.parse("2026-06-21T10:00:00Z"),
            receivedAt = Instant.parse("2026-06-21T10:00:01Z"),
            sourceSequence = 1L,
            privacyClass = PrivacyClass.HIGH,
            messageId = MessageId(5L),
            authorId = AuthorId(userId),
            content = MessageContent.Available(secret),
            replyTo = null,
            mentions = emptySet(),
            attachments = emptyList(),
            threadId = null,
        )

    private fun envelope(event: NormalizedDiscordEvent): IngestEnvelope =
        IngestEnvelope(
            event = event,
            receivedAt = event.receivedAt,
            shardId = ShardId.NO_SHARD,
            sessionId = null,
            gatewaySequence = null,
            mapperVersion = MapperVersion("test-1"),
            consentSnapshot = ConsentSnapshot(observationAllowed = true, speechAllowed = false),
        )

    @Test
    fun `동의 미허용이면 적재하지 않고 REJECTED_CONSENT 를 돌려준다`() {
        val store = FakeEventStore()
        val service = IngestDiscordEventService(ConsentPolicyPort { _, _, _ -> ConsentDecision.DENIED }, store)

        val outcome = service.ingest(envelope(message("evt-blocked")))

        assertEquals(IngestOutcome.REJECTED_CONSENT, outcome)
        assertTrue(store.eventIds.isEmpty(), "미동의 → 적재 0")
        assertTrue(store.outboxIds.isEmpty(), "미동의 → outbox 0")
    }

    @Test
    fun `동의 허용이면 event 와 outbox 를 같이 적재한다`() {
        val store = FakeEventStore()
        val service =
            IngestDiscordEventService(ConsentPolicyPort { _, _, _ -> ConsentDecision.OBSERVE_AND_SPEAK }, store)

        val outcome = service.ingest(envelope(message("evt-ok")))

        assertEquals(IngestOutcome.APPENDED, outcome)
        assertEquals(listOf("evt-ok"), store.eventIds)
        assertEquals(listOf("evt-ok"), store.outboxIds, "event 와 outbox 가 같이 기록(원자성)")
    }

    @Test
    fun `중복 재수신은 DUPLICATE 로 멱등 흡수한다`() {
        val store = FakeEventStore()
        val service =
            IngestDiscordEventService(ConsentPolicyPort { _, _, _ -> ConsentDecision.OBSERVE_AND_SPEAK }, store)

        assertEquals(IngestOutcome.APPENDED, service.ingest(envelope(message("evt-idem"))))
        assertEquals(IngestOutcome.DUPLICATE, service.ingest(envelope(message("evt-idem"))))

        assertEquals(1, store.eventIds.size, "중복 적재 없음")
        assertEquals(1, store.outboxIds.size, "중복 outbox side effect 없음")
    }

    @Test
    fun `옵트아웃 사용자는 같은 채널이어도 적재되지 않는다`() {
        val store = FakeEventStore()
        // userId 만 거부하는 동의 포트.
        val service =
            IngestDiscordEventService(
                ConsentPolicyPort { _, u, _ ->
                    if (u == userId) ConsentDecision.DENIED else ConsentDecision.OBSERVE_AND_SPEAK
                },
                store,
            )

        assertEquals(IngestOutcome.REJECTED_CONSENT, service.ingest(envelope(message("evt-optout"))))
        assertTrue(store.eventIds.isEmpty())
    }
}
