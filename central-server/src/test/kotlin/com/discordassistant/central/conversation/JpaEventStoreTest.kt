package com.discordassistant.central.conversation

import com.discordassistant.central.conversation.adapter.outbound.persistence.JpaEventStore
import com.discordassistant.central.conversation.adapter.outbound.persistence.NexaConversationOutboxRepository
import com.discordassistant.central.conversation.adapter.outbound.persistence.NexaEventRepository
import com.discordassistant.central.conversation.adapter.outbound.persistence.OutboxStatus
import com.discordassistant.central.conversation.application.port.out.AppendResult
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GenericObservedEvent
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P03-T009/T010/T012 event store JPA 어댑터 통합 테스트(H2 + Flyway V51).
 *
 * - T010 acceptance: 중복 insert 가 데이터 중복 없이 명시적 결과(APPENDED/DUPLICATE)를 반환한다(eventId 유니크).
 * - 채널 순서(sourceSequence → occurredAt)·범위 스트림·redaction 상태 전이.
 * - T011/T012: append 가 같은 트랜잭션에서 outbox 행을 같이 만든다(event 만 / outbox 만 인 상태 없음).
 * - T009: 원문(content_cipher)은 기본 미저장(null).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaEventStoreTest
    @Autowired
    constructor(
        private val events: NexaEventRepository,
        private val outbox: NexaConversationOutboxRepository,
    ) {
        private val clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC)
        private val store = JpaEventStore(events, outbox, clock)

        private fun event(
            id: String,
            channel: Long = 10L,
            seq: Long,
            occurred: String = "2026-06-21T10:00:00Z",
            received: String = "2026-06-21T10:00:01Z",
        ): NormalizedDiscordEvent =
            GenericObservedEvent(
                eventId = EventId(id),
                guildId = GuildId(1L),
                channelId = ChannelId(channel),
                occurredAt = Instant.parse(occurred),
                receivedAt = Instant.parse(received),
                sourceSequence = seq,
                privacyClass = PrivacyClass.LOW,
            )

        @Test
        fun `append 는 신규 이벤트를 적재하고 같은 트랜잭션에서 outbox 행을 만든다`() {
            val result = store.append(event("evt-1", seq = 1))

            assertEquals(AppendResult.APPENDED, result)
            assertEquals(1, events.count())
            assertEquals(1, outbox.count())
            val saved = events.findByEventId("evt-1")!!
            assertEquals(10L, saved.channelId)
            assertNull(saved.contentCipher, "원문(content_cipher)은 기본 미저장")
            assertFalse(saved.redacted)
            val box = outbox.findByStatusOrderByIdAsc(OutboxStatus.PENDING.name).single()
            assertEquals("evt-1", box.eventId)
        }

        @Test
        fun `중복 재수신 append 는 데이터 중복 없이 DUPLICATE 를 반환한다`() {
            assertEquals(AppendResult.APPENDED, store.append(event("evt-dup", seq = 1)))
            assertEquals(AppendResult.DUPLICATE, store.append(event("evt-dup", seq = 1)))
            assertEquals(AppendResult.DUPLICATE, store.append(event("evt-dup", seq = 1)))

            // eventId 유니크 — 행이 하나뿐(중복 적재 없음). outbox 도 한 행(중복 side effect 없음).
            assertEquals(1, events.count())
            assertEquals(1, outbox.count())
        }

        @Test
        fun `streamByChannel 은 채널별 sourceSequence occurredAt 순서로 돌려준다`() {
            store.append(event("c1-s2", channel = 77, seq = 2))
            store.append(event("c1-s1", channel = 77, seq = 1))
            store.append(event("other", channel = 99, seq = 1))

            val stream = store.streamByChannel(ChannelId(77))

            assertEquals(listOf("c1-s1", "c1-s2"), stream.map { it.eventId.value })
            assertTrue(stream.all { it.channelId == ChannelId(77) }, "다른 채널 이벤트는 섞이지 않는다")
        }

        @Test
        fun `streamByRange 는 from 포함 to 미포함 수신 범위만 돌려준다`() {
            store.append(event("r-before", seq = 1, received = "2026-06-21T09:59:59Z"))
            store.append(event("r-in", seq = 2, received = "2026-06-21T10:00:00Z"))
            store.append(event("r-edge", seq = 3, received = "2026-06-21T11:00:00Z"))

            val stream =
                store.streamByRange(
                    from = Instant.parse("2026-06-21T10:00:00Z"),
                    to = Instant.parse("2026-06-21T11:00:00Z"),
                )

            assertEquals(listOf("r-in"), stream.map { it.eventId.value }, "from 포함·to 미포함")
        }

        @Test
        fun `markRedacted 는 행을 지우지 않고 redaction 상태로 전이한다 멱등`() {
            store.append(event("evt-redact", seq = 1))

            assertTrue(store.markRedacted(EventId("evt-redact")))
            // 이미 redaction 됐으면 false(멱등 — 중복 호출이 추가 side effect 없음).
            assertFalse(store.markRedacted(EventId("evt-redact")))
            // 존재하지 않으면 false.
            assertFalse(store.markRedacted(EventId("nope")))

            val saved = events.findByEventId("evt-redact")!!
            assertTrue(saved.redacted, "행은 보존되고 상태만 전이")
            assertEquals(clock.instant(), saved.redactedAt)
            assertEquals(1, events.count(), "redaction 은 행을 삭제하지 않는다")
            // 스트림에 여전히 존재(순서 보존)하되 redacted=true.
            assertTrue(store.streamByChannel(ChannelId(10)).single().redacted)
        }

        @Test
        fun `exists 는 적재 여부를 답한다`() {
            assertFalse(store.exists(EventId("ghost")))
            store.append(event("ghost", seq = 1))
            assertTrue(store.exists(EventId("ghost")))
        }
    }
