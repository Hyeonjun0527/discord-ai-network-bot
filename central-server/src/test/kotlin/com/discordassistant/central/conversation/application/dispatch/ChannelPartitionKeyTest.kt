package com.discordassistant.central.conversation.application.dispatch

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GenericObservedEvent
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P03-T013 acceptance: partition key = channelId(ADR 0011, 스레드 분리).
 *
 * 같은 채널 이벤트는 같은 파티션 키, 다른 채널(스레드 포함 — 자기 channelId)은 다른 파티션 키임을 증명한다.
 */
class ChannelPartitionKeyTest {
    private fun event(
        channel: Long,
        id: String = "e-$channel",
    ): NormalizedDiscordEvent =
        GenericObservedEvent(
            eventId = EventId(id),
            guildId = GuildId(1L),
            channelId = ChannelId(channel),
            occurredAt = Instant.parse("2026-06-21T10:00:00Z"),
            receivedAt = Instant.parse("2026-06-21T10:00:01Z"),
            sourceSequence = 1,
            privacyClass = PrivacyClass.LOW,
        )

    @Test
    fun `같은 채널 이벤트는 같은 파티션 키다`() {
        val a = ChannelPartitionKey.of(event(channel = 100, id = "a"))
        val b = ChannelPartitionKey.of(event(channel = 100, id = "b"))
        assertEquals(a, b)
        assertEquals(ChannelId(100), a.channelId)
    }

    @Test
    fun `다른 채널 스레드 포함 은 다른 파티션 키로 분리된다`() {
        // 스레드는 자기 고유 channelId(부모 200 != 스레드 201) — ADR 0011 separate.
        val parent = ChannelPartitionKey.of(event(channel = 200))
        val thread = ChannelPartitionKey.of(event(channel = 201))
        assertNotEquals(parent, thread)
    }

    @Test
    fun `channelId 직접 생성과 이벤트 생성이 일치한다`() {
        assertEquals(
            ChannelPartitionKey.of(ChannelId(300)),
            ChannelPartitionKey.of(event(channel = 300)),
        )
    }
}
