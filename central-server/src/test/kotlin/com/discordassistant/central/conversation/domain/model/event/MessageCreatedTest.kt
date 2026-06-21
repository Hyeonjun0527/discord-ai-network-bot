package com.discordassistant.central.conversation.domain.model.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P02-T016 MessageCreated acceptance:
 * "MESSAGE_CONTENT 미허용(인텐트 없음)" 과 "content unavailable(빈 메시지)" 를 단일 null 로 뭉개지 말고
 * 명시 타입으로 구분한다.
 */
class MessageCreatedTest {
    private fun event(content: MessageContent): MessageCreated =
        MessageCreated(
            eventId = EventId("evt-mc-1"),
            guildId = GuildId(1L),
            channelId = ChannelId(2L),
            occurredAt = Instant.parse("2026-06-21T10:00:00Z"),
            receivedAt = Instant.parse("2026-06-21T10:00:01Z"),
            sourceSequence = 1L,
            privacyClass = PrivacyClass.HIGH,
            messageId = MessageId(10L),
            authorId = AuthorId(20L),
            content = content,
            replyTo = null,
            mentions = emptySet(),
            attachments = emptyList(),
            threadId = null,
        )

    @Test
    fun `인텐트 미허용과 빈 메시지는 서로 다른 타입으로 구분된다`() {
        val intentMissing = event(MessageContent.Unavailable.IntentMissing).content
        val empty = event(MessageContent.Unavailable.Empty).content

        // 둘 다 Unavailable 이지만 동일하지 않다 — 단일 null 로 뭉개지지 않음.
        assertInstanceOf(MessageContent.Unavailable::class.java, intentMissing)
        assertInstanceOf(MessageContent.Unavailable::class.java, empty)
        assertNotEquals(intentMissing, empty)
    }

    @Test
    fun `available 은 원문 텍스트를 보존하고 unavailable 과 구분된다`() {
        val available = event(MessageContent.Available("닉네임")).content
        assertInstanceOf(MessageContent.Available::class.java, available)
        assertEquals("닉네임", (available as MessageContent.Available).text)
        assertNotEquals(available, MessageContent.Unavailable.Empty)
        assertNotEquals(available, MessageContent.Unavailable.IntentMissing)
    }

    @Test
    fun `when 분기로 세 상태를 모두 망라할 수 있다`() {
        val states =
            listOf(
                MessageContent.Available("x"),
                MessageContent.Unavailable.IntentMissing,
                MessageContent.Unavailable.Empty,
            )
        val labels =
            states.map {
                when (it) {
                    is MessageContent.Available -> "has-text"
                    MessageContent.Unavailable.IntentMissing -> "no-permission"
                    MessageContent.Unavailable.Empty -> "empty"
                }
            }
        assertEquals(listOf("has-text", "no-permission", "empty"), labels)
        assertTrue(labels.toSet().size == 3)
    }
}
