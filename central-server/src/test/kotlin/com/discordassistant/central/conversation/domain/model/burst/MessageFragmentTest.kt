package com.discordassistant.central.conversation.domain.model.burst

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P04-T001 acceptance: JDA/JPA 의존 없이 동등성·정렬 규칙이 테스트된다.
 */
class MessageFragmentTest {
    private val t0 = Instant.parse("2026-01-01T11:15:01Z")

    private fun fragment(
        messageId: Long,
        authorId: Long = 1L,
        seq: Long = messageId,
        at: Instant = t0,
        channelId: Long = 100L,
        threadId: Long? = null,
    ): MessageFragment =
        MessageFragment(
            messageId = MessageId(messageId),
            authorId = AuthorId(authorId),
            channelId = ChannelId(channelId),
            sourceSequence = seq,
            occurredAt = at,
            content = MessageContent.Available("hi"),
            replyTo = null,
            threadId = threadId?.let { ChannelId(it) },
            type = FragmentType.NORMAL,
        )

    @Test
    fun `같은 필드면 동등하고 hashCode 도 같다 (데이터 클래스)`() {
        assertEquals(fragment(1), fragment(1))
        assertEquals(fragment(1).hashCode(), fragment(1).hashCode())
        assertNotEquals(fragment(1), fragment(2))
    }

    @Test
    fun `정렬은 sourceSequence 우선 전순서다`() {
        val a = fragment(messageId = 10, seq = 1)
        val b = fragment(messageId = 11, seq = 2)
        val sorted = listOf(b, a).sortedWith(MessageFragment.chronology)
        assertEquals(listOf(a, b), sorted)
    }

    @Test
    fun `같은 seq 면 occurredAt 으로, 같은 시각이면 messageId 로 결정론적 타이브레이크`() {
        val later = fragment(messageId = 1, seq = 5, at = t0.plusSeconds(1))
        val earlier = fragment(messageId = 2, seq = 5, at = t0)
        assertEquals(listOf(earlier, later), listOf(later, earlier).sortedWith(MessageFragment.chronology))

        // 동일 seq·동일 시각 → messageId 오름차순.
        val m1 = fragment(messageId = 1, seq = 5, at = t0)
        val m2 = fragment(messageId = 2, seq = 5, at = t0)
        assertEquals(listOf(m1, m2), listOf(m2, m1).sortedWith(MessageFragment.chronology))
    }

    @Test
    fun `locationKey 는 thread 가 다르면 다르다`() {
        val inChannel = fragment(messageId = 1, channelId = 100L, threadId = null)
        val inThread = fragment(messageId = 1, channelId = 100L, threadId = 200L)
        assertNotEquals(inChannel.locationKey, inThread.locationKey)
    }

    @Test
    fun `FragmentType 별 다른 작성자 종료 규칙이 명시된다`() {
        assertEquals(true, FragmentType.NORMAL.endsOtherAuthorBurst)
        assertEquals(false, FragmentType.EMOJI.endsOtherAuthorBurst)
        assertEquals(false, FragmentType.SYSTEM.endsOtherAuthorBurst)
    }
}
