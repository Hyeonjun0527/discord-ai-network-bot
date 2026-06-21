package com.discordassistant.central.conversation.domain.model.event

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P02-T017 MessageUpdated acceptance:
 * 동일 revision 중복(at-least-once)과 역순 revision 도착 처리 규칙을 검증한다.
 */
class MessageUpdatedTest {
    private fun update(
        messageId: Long = 10L,
        revision: Long,
        content: MessageContent = MessageContent.Available("edited"),
    ): MessageUpdated =
        MessageUpdated(
            eventId = EventId("evt-mu-$messageId-$revision"),
            guildId = GuildId(1L),
            channelId = ChannelId(2L),
            occurredAt = Instant.parse("2026-06-21T10:00:00Z"),
            receivedAt = Instant.parse("2026-06-21T10:00:01Z"),
            sourceSequence = 1L,
            privacyClass = PrivacyClass.HIGH,
            messageId = MessageId(messageId),
            revision = revision,
            content = content,
        )

    @Test
    fun `더 큰 revision 만 이전 편집을 덮어쓴다`() {
        val r2 = update(revision = 2)
        val r1 = update(revision = 1)
        assertTrue(r2.supersedes(r1))
        assertFalse(r1.supersedes(r2))
    }

    @Test
    fun `동일 revision 중복은 덮어쓰지 않고 stale 로 본다 (idempotent)`() {
        val a = update(revision = 3)
        val b = update(revision = 3)
        assertFalse(a.supersedes(b))
        assertTrue(a.isStaleAgainst(b))
        assertTrue(b.isStaleAgainst(a))
    }

    @Test
    fun `역순 도착한 과거 revision 은 stale 이라 현재 상태를 바꾸지 않는다`() {
        val newer = update(revision = 5)
        val olderArrivingLate = update(revision = 4)
        assertTrue(olderArrivingLate.isStaleAgainst(newer))
        assertFalse(olderArrivingLate.supersedes(newer))
    }

    @Test
    fun `다른 messageId 는 서로 비교 대상이 아니다`() {
        val a = update(messageId = 10L, revision = 2)
        val b = update(messageId = 99L, revision = 1)
        assertFalse(a.supersedes(b))
        assertFalse(a.isStaleAgainst(b))
    }
}
