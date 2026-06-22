package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageId
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P03-T004 수정·삭제 매퍼 acceptance:
 * 캐시 미스(이전 내용 없음)에서도 최소 키로 이벤트 생성, 예외로 유실 안 됨 + revision-aware 정규화.
 */
class JdaMessageRevisionMapperTest {
    private val mapper = JdaMessageRevisionMapper()
    private val occurredAt = Instant.parse("2026-06-21T10:00:00Z")
    private val receivedAt = Instant.parse("2026-06-21T10:00:01Z")

    private fun updateSnapshot(
        revision: Long,
        content: ContentSnapshot,
    ): MessageUpdatedSnapshot =
        MessageUpdatedSnapshot(
            guildId = 1L,
            channelId = 2L,
            messageId = 10L,
            revision = revision,
            content = content,
            occurredAt = occurredAt,
            receivedAt = receivedAt,
            sourceSequence = 1L,
        )

    private fun deleteSnapshot(): MessageDeletedSnapshot =
        MessageDeletedSnapshot(
            guildId = 1L,
            channelId = 2L,
            messageId = 10L,
            occurredAt = occurredAt,
            receivedAt = receivedAt,
            sourceSequence = 1L,
        )

    @Test
    fun `이전 내용 없는 수정도 Unavailable 로 이벤트가 생성된다`() {
        val event = mapper.toUpdated(updateSnapshot(revision = 0L, content = ContentSnapshot.IntentMissing))
        assertEquals(MessageContent.Unavailable.IntentMissing, event.content)
        assertEquals(MessageId(10L), event.messageId)
        assertEquals(0L, event.revision)
    }

    @Test
    fun `더 큰 revision 이 이전 편집을 덮어쓴다`() {
        val rev1 = mapper.toUpdated(updateSnapshot(revision = 1L, content = ContentSnapshot.Readable("a")))
        val rev2 = mapper.toUpdated(updateSnapshot(revision = 2L, content = ContentSnapshot.Readable("b")))
        assertTrue(rev2.supersedes(rev1))
        assertTrue(rev1.isStaleAgainst(rev2))
    }

    @Test
    fun `다른 revision 은 다른 eventId 라 충돌하지 않는다`() {
        val rev1 = mapper.toUpdated(updateSnapshot(revision = 1L, content = ContentSnapshot.Readable("a")))
        val rev2 = mapper.toUpdated(updateSnapshot(revision = 2L, content = ContentSnapshot.Readable("b")))
        assertNotEquals(rev1.eventId, rev2.eventId)
    }

    @Test
    fun `삭제는 messageId 만으로 최소 키 이벤트를 만든다 (캐시 미스 무관)`() {
        val event = mapper.toDeleted(deleteSnapshot())
        assertEquals(MessageId(10L), event.messageId)
        assertEquals(GuildId(1L), event.guildId)
        assertEquals(ChannelId(2L), event.channelId)
        assertEquals(PrivacyClass.LOW, event.privacyClass)
        assertTrue(event.targets(MessageId(10L)))
    }

    @Test
    fun `같은 메시지 삭제 재수신은 같은 eventId (idempotent)`() {
        val a = mapper.toDeleted(deleteSnapshot())
        val b = mapper.toDeleted(deleteSnapshot())
        assertEquals(a.eventId, b.eventId)
        assertEquals(a, b)
    }

    @Test
    fun `수정과 삭제는 같은 메시지라도 eventId 가 충돌하지 않는다`() {
        val updated = mapper.toUpdated(updateSnapshot(revision = 0L, content = ContentSnapshot.Readable("a")))
        val deleted = mapper.toDeleted(deleteSnapshot())
        assertNotEquals(updated.eventId, deleted.eventId)
    }
}
