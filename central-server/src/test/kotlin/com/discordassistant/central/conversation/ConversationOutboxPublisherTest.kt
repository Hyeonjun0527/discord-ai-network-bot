package com.discordassistant.central.conversation

import com.discordassistant.central.conversation.adapter.outbound.persistence.ConversationOutboxPublisher
import com.discordassistant.central.conversation.adapter.outbound.persistence.JpaEventStore
import com.discordassistant.central.conversation.adapter.outbound.persistence.NexaConversationOutboxRepository
import com.discordassistant.central.conversation.adapter.outbound.persistence.NexaEventRepository
import com.discordassistant.central.conversation.adapter.outbound.persistence.OutboxStatus
import com.discordassistant.central.conversation.application.port.out.ConversationProjectionPort
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GenericObservedEvent
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P03-T012 outbox publisher 통합 테스트(H2 + Flyway V51).
 *
 * acceptance: 재시도 시 같은 outbox record 가 중복 side effect 를 만들지 않는다.
 * - publisher 는 PENDING 만 집어 전달 후 PUBLISHED 로 전이 — 다시 publish 해도 같은 record 를 재전달하지 않는다.
 * - 전달 실패 시 PENDING 유지(attempts 증가) → 다음 publish 에서 재시도. 소비자 멱등 전제로 중복 side effect 없음.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ConversationOutboxPublisherTest
    @Autowired
    constructor(
        private val events: NexaEventRepository,
        private val outbox: NexaConversationOutboxRepository,
    ) {
        private val clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC)
        private val store = JpaEventStore(events, outbox, clock)

        private fun event(id: String): NormalizedDiscordEvent =
            GenericObservedEvent(
                eventId = EventId(id),
                guildId = GuildId(1L),
                channelId = ChannelId(10L),
                occurredAt = Instant.parse("2026-06-21T10:00:00Z"),
                receivedAt = Instant.parse("2026-06-21T10:00:01Z"),
                sourceSequence = 1L,
                privacyClass = PrivacyClass.LOW,
            )

        /** 전달 받은 (eventId) 를 기록하는 fake projection — 멱등 소비 검증용. */
        private class RecordingProjection : ConversationProjectionPort {
            val delivered = mutableListOf<String>()

            override fun deliver(
                eventId: EventId,
                channelId: ChannelId,
            ) {
                delivered += eventId.value
            }
        }

        @Test
        fun `publishPending 은 PENDING 만 전달하고 PUBLISHED 로 전이하며 재실행해도 재전달하지 않는다`() {
            store.append(event("evt-1"))
            store.append(event("evt-2"))
            val projection = RecordingProjection()
            val publisher = ConversationOutboxPublisher(outbox, projection, clock)

            val first = publisher.publishPending()
            assertEquals(2, first)
            assertEquals(listOf("evt-1", "evt-2"), projection.delivered)
            assertTrue(outbox.findByStatusOrderByIdAsc(OutboxStatus.PENDING.name).isEmpty(), "모두 PUBLISHED")

            // 재실행: 이미 PUBLISHED 라 같은 record 를 다시 전달하지 않는다(중복 side effect 없음).
            val second = publisher.publishPending()
            assertEquals(0, second)
            assertEquals(listOf("evt-1", "evt-2"), projection.delivered, "재실행에 중복 전달 없음")
        }

        @Test
        fun `전달 실패는 PENDING 유지 attempts 증가 후 다음 실행에서 재시도된다`() {
            store.append(event("evt-flaky"))
            var failFirst = true
            val projection =
                ConversationProjectionPort { _, _ ->
                    if (failFirst) {
                        failFirst = false
                        throw IllegalStateException("일시 전달 실패")
                    }
                }
            val publisher = ConversationOutboxPublisher(outbox, projection, clock)

            // 1차: 실패 → PENDING 유지, attempts=1.
            assertEquals(0, publisher.publishPending())
            val afterFail = outbox.findByStatusOrderByIdAsc(OutboxStatus.PENDING.name).single()
            assertEquals(1, afterFail.attempts)

            // 2차: 성공 → PUBLISHED, attempts=2(한 record 만, 중복 side effect 없음).
            assertEquals(1, publisher.publishPending())
            assertTrue(outbox.findByStatusOrderByIdAsc(OutboxStatus.PENDING.name).isEmpty())
            val published = outbox.findAll().single { it.eventId == "evt-flaky" }
            assertEquals(OutboxStatus.PUBLISHED.name, published.status)
            assertEquals(2, published.attempts)
        }
    }
