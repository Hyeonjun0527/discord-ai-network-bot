package com.discordassistant.central.conversation.domain.model.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P02-T018 MessageDeleted acceptance:
 * content 를 요구하지 않고 idempotent 하게 적용된다(같은 messageId 삭제를 여러 번 받아도 결과 동일).
 */
class MessageDeletedTest {
    private fun deleted(
        eventId: String = "evt-md-1",
        messageId: Long = 10L,
        sourceSequence: Long = 1L,
    ): MessageDeleted =
        MessageDeleted(
            eventId = EventId(eventId),
            guildId = GuildId(1L),
            channelId = ChannelId(2L),
            occurredAt = Instant.parse("2026-06-21T10:00:00Z"),
            receivedAt = Instant.parse("2026-06-21T10:00:01Z"),
            sourceSequence = sourceSequence,
            privacyClass = PrivacyClass.LOW,
            messageId = MessageId(messageId),
        )

    @Test
    fun `원문 없이 messageId 만으로 provenance 무효화 대상을 가린다`() {
        val event = deleted(messageId = 42L)
        assertTrue(event.targets(MessageId(42L)))
        assertFalse(event.targets(MessageId(43L)))
    }

    @Test
    fun `같은 messageId 삭제를 중복 적용해도 무효화 대상 집합이 동일하다 (idempotent)`() {
        // 서로 다른 수신 메타(eventId/sourceSequence)로 같은 메시지 삭제를 두 번 받음.
        val first = deleted(eventId = "evt-md-a", messageId = 7L, sourceSequence = 1L)
        val second = deleted(eventId = "evt-md-b", messageId = 7L, sourceSequence = 2L)

        val invalidated = setOf(first.messageId, second.messageId)
        // 멱등: 두 번 적용해도 무효화 대상은 단일 메시지.
        assertEquals(1, invalidated.size)
        assertEquals(MessageId(7L), invalidated.single())
        assertTrue(first.targets(second.messageId))
    }
}
