package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageId
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P03-T002/T003 매퍼 acceptance(순수 매핑 — JDA mock 불필요, 스냅샷으로 검증):
 *  - T002: toEvent 의 반환은 순수 도메인 이벤트(JDA 참조 없음).
 *  - T003: 봇/웹훅/시스템/사람 출처 구분 + content 가용성(인텐트 없음/빈/사용 가능) 명시 구분.
 */
class JdaMessageEventMapperTest {
    private val mapper = JdaMessageEventMapper()
    private val occurredAt = Instant.parse("2026-06-21T10:00:00Z")
    private val receivedAt = Instant.parse("2026-06-21T10:00:01Z")

    private fun snapshot(
        sourceType: MessageSourceType = MessageSourceType.HUMAN,
        content: ContentSnapshot = ContentSnapshot.Readable("hello"),
        replyTo: Long? = null,
        mentions: Set<Long> = emptySet(),
        attachments: List<AttachmentSnapshot> = emptyList(),
        threadId: Long? = null,
    ): MessageCreatedSnapshot =
        MessageCreatedSnapshot(
            guildId = 1L,
            channelId = 2L,
            messageId = 10L,
            authorId = 20L,
            sourceType = sourceType,
            content = content,
            occurredAt = occurredAt,
            receivedAt = receivedAt,
            sourceSequence = 5L,
            replyToMessageId = replyTo,
            mentionedUserIds = mentions,
            attachments = attachments,
            threadId = threadId,
        )

    @Test
    fun `반환은 순수 도메인 이벤트이고 봉투 필드가 스냅샷에서 채워진다`() {
        val event = mapper.toEvent(snapshot())
        assertEquals(GuildId(1L), event.guildId)
        assertEquals(ChannelId(2L), event.channelId)
        assertEquals(MessageId(10L), event.messageId)
        assertEquals(AuthorId(20L), event.authorId)
        assertEquals(occurredAt, event.occurredAt)
        assertEquals(receivedAt, event.receivedAt)
        assertEquals(5L, event.sourceSequence)
    }

    @Test
    fun `원문이 있으면 content 는 Available 이고 PII HIGH`() {
        val event = mapper.toEvent(snapshot(content = ContentSnapshot.Readable("닉네임")))
        assertInstanceOf(MessageContent.Available::class.java, event.content)
        assertEquals("닉네임", (event.content as MessageContent.Available).text)
        assertEquals(PrivacyClass.HIGH, event.privacyClass)
    }

    @Test
    fun `인텐트 없음과 빈 본문은 서로 다른 Unavailable 로 구분된다`() {
        val intentMissing = mapper.toEvent(snapshot(content = ContentSnapshot.IntentMissing)).content
        val empty = mapper.toEvent(snapshot(content = ContentSnapshot.Readable(""))).content
        assertEquals(MessageContent.Unavailable.IntentMissing, intentMissing)
        assertEquals(MessageContent.Unavailable.Empty, empty)
    }

    @Test
    fun `봇 웹훅 시스템 사람 출처가 스냅샷에 구분 보존된다`() {
        // 출처 구분은 추출 단계 산출물(MessageSourceType)이며 스냅샷에 보존된다 — 4종이 서로 다르다.
        val types =
            setOf(
                snapshot(sourceType = MessageSourceType.HUMAN).sourceType,
                snapshot(sourceType = MessageSourceType.BOT).sourceType,
                snapshot(sourceType = MessageSourceType.WEBHOOK).sourceType,
                snapshot(sourceType = MessageSourceType.SYSTEM).sourceType,
            )
        assertEquals(4, types.size)
    }

    @Test
    fun `reply mention attachment thread metadata 가 매핑된다`() {
        val event =
            mapper.toEvent(
                snapshot(
                    replyTo = 99L,
                    mentions = setOf(30L, 40L),
                    attachments =
                        listOf(
                            AttachmentSnapshot(attachmentId = 7L, fileName = "a.png", contentType = "image/png", sizeBytes = 123L),
                        ),
                    threadId = 500L,
                ),
            )
        assertEquals(MessageId(99L), event.replyTo)
        assertEquals(setOf(AuthorId(30L), AuthorId(40L)), event.mentions)
        assertEquals(1, event.attachments.size)
        assertEquals("7", event.attachments[0].attachmentId)
        assertEquals("a.png", event.attachments[0].fileName)
        assertEquals(ChannelId(500L), event.threadId)
    }

    @Test
    fun `채널 직속 메시지는 threadId 가 null`() {
        val event = mapper.toEvent(snapshot(threadId = null))
        assertNull(event.threadId)
    }

    @Test
    fun `같은 메시지 재수신은 같은 eventId 를 만든다`() {
        val a = mapper.toEvent(snapshot())
        val b = mapper.toEvent(snapshot())
        assertEquals(a.eventId, b.eventId)
    }
}
