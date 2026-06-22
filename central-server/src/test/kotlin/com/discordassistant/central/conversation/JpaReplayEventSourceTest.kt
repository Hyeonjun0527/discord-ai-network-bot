package com.discordassistant.central.conversation

import com.discordassistant.central.conversation.adapter.outbound.persistence.JpaEventStore
import com.discordassistant.central.conversation.adapter.outbound.persistence.JpaReplayEventSource
import com.discordassistant.central.conversation.adapter.outbound.persistence.NexaConversationOutboxRepository
import com.discordassistant.central.conversation.adapter.outbound.persistence.NexaEventRepository
import com.discordassistant.central.conversation.application.replay.ReplayCriteria
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GenericObservedEvent
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P03-T019 replay 이벤트 소스 JPA 어댑터 통합 테스트(H2 + Flyway). guild/channel/time range 로 저장된
 * 이벤트를 결정론적 순서로 읽어 재생 키만 돌려준다(읽기 전용, 원문 미포함).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaReplayEventSourceTest
    @Autowired
    constructor(
        private val events: NexaEventRepository,
        private val outbox: NexaConversationOutboxRepository,
    ) {
        private val clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC)
        private val store = JpaEventStore(events, outbox, clock)
        private val source = JpaReplayEventSource(events)

        private fun seed(
            id: String,
            guild: Long = 1L,
            channel: Long = 10L,
            seq: Long,
            occurred: String,
        ): NormalizedDiscordEvent =
            GenericObservedEvent(
                eventId = EventId(id),
                guildId = GuildId(guild),
                channelId = ChannelId(channel),
                occurredAt = Instant.parse(occurred),
                receivedAt = Instant.parse("2026-06-21T10:00:01Z"),
                sourceSequence = seq,
                privacyClass = PrivacyClass.LOW,
            )

        @Test
        fun `guild 전체 범위는 채널 순서로 재생 키를 돌려준다`() {
            store.append(seed("g1-c10-s2", channel = 10, seq = 2, occurred = "2026-06-21T10:00:02Z"))
            store.append(seed("g1-c10-s1", channel = 10, seq = 1, occurred = "2026-06-21T10:00:01Z"))
            store.append(seed("g1-c20-s1", channel = 20, seq = 1, occurred = "2026-06-21T10:00:01Z"))
            store.append(seed("g2-c10-s1", guild = 2, channel = 10, seq = 1, occurred = "2026-06-21T10:00:01Z"))

            val keys =
                source.streamForReplay(
                    ReplayCriteria(
                        guildId = GuildId(1L),
                        channelId = null,
                        from = Instant.parse("2026-06-21T00:00:00Z"),
                        to = Instant.parse("2026-06-22T00:00:00Z"),
                        projectionVersion = 1,
                    ),
                )

            // guild 1 만, 채널별 순서(channel 10: s1,s2 → channel 20: s1). guild 2 제외.
            assertEquals(listOf("g1-c10-s1", "g1-c10-s2", "g1-c20-s1"), keys.map { it.eventId.value })
        }

        @Test
        fun `channel 지정 시 그 채널만 sourceSequence 순서로 돌려준다`() {
            store.append(seed("c10-s2", channel = 10, seq = 2, occurred = "2026-06-21T10:00:02Z"))
            store.append(seed("c10-s1", channel = 10, seq = 1, occurred = "2026-06-21T10:00:01Z"))
            store.append(seed("c20-s1", channel = 20, seq = 1, occurred = "2026-06-21T10:00:01Z"))

            val keys =
                source.streamForReplay(
                    ReplayCriteria(
                        guildId = GuildId(1L),
                        channelId = ChannelId(10L),
                        from = Instant.parse("2026-06-21T00:00:00Z"),
                        to = Instant.parse("2026-06-22T00:00:00Z"),
                        projectionVersion = 1,
                    ),
                )

            assertEquals(listOf("c10-s1", "c10-s2"), keys.map { it.eventId.value }, "지정 채널만")
        }

        @Test
        fun `시각 범위는 from 포함 to 미포함이다`() {
            store.append(seed("before", seq = 1, occurred = "2026-06-21T09:59:59Z"))
            store.append(seed("in", seq = 2, occurred = "2026-06-21T10:00:00Z"))
            store.append(seed("edge", seq = 3, occurred = "2026-06-21T11:00:00Z"))

            val keys =
                source.streamForReplay(
                    ReplayCriteria(
                        guildId = GuildId(1L),
                        channelId = ChannelId(10L),
                        from = Instant.parse("2026-06-21T10:00:00Z"),
                        to = Instant.parse("2026-06-21T11:00:00Z"),
                        projectionVersion = 1,
                    ),
                )

            assertEquals(listOf("in"), keys.map { it.eventId.value }, "from 포함·to 미포함(occurred 기준)")
        }
    }
