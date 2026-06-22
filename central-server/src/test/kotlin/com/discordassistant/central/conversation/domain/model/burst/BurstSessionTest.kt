package com.discordassistant.central.conversation.domain.model.burst

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageId
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P04-T003 acceptance: 서로 다른 채널·thread 의 세션이 섞이지 않는다.
 */
class BurstSessionTest {
    private val guild = GuildId(1L)
    private val t0 = Instant.parse("2026-01-01T11:15:01Z")

    private fun fragment(
        authorId: Long,
        channelId: Long = 100L,
        threadId: Long? = null,
    ): MessageFragment =
        MessageFragment(
            messageId = MessageId(authorId * 10),
            authorId = AuthorId(authorId),
            channelId = ChannelId(channelId),
            sourceSequence = authorId,
            occurredAt = t0,
            content = MessageContent.Available("hi"),
            replyTo = null,
            threadId = threadId?.let { ChannelId(it) },
            type = FragmentType.NORMAL,
        )

    @Test
    fun `세션은 작성자별 OPEN 버스트를 추적한다`() {
        val loc = BurstLocationKey(ChannelId(100L), null)
        val session =
            BurstSession
                .empty(loc)
                .withOpenBurst(UtteranceBurst.open(guild, fragment(1)))
                .withOpenBurst(UtteranceBurst.open(guild, fragment(2)))
        assertEquals(AuthorId(1L), session.openBurstOf(AuthorId(1L))?.authorId)
        assertEquals(AuthorId(2L), session.openBurstOf(AuthorId(2L))?.authorId)
        assertNull(session.openBurstOf(AuthorId(3L)))
    }

    @Test
    fun `채널 세션과 thread 세션은 별개 인스턴스라 섞이지 않는다`() {
        val channelLoc = BurstLocationKey(ChannelId(100L), null)
        val threadLoc = BurstLocationKey(ChannelId(100L), ChannelId(200L))

        val channelSession =
            BurstSession
                .empty(channelLoc)
                .withOpenBurst(UtteranceBurst.open(guild, fragment(1, channelId = 100L, threadId = null)))
        val threadSession =
            BurstSession
                .empty(threadLoc)
                .withOpenBurst(UtteranceBurst.open(guild, fragment(1, channelId = 100L, threadId = 200L)))

        // 같은 작성자가 두 위치에서 OPEN 이지만 두 세션이 독립적이다.
        assertEquals(channelLoc, channelSession.openBurstOf(AuthorId(1L))?.location)
        assertEquals(threadLoc, threadSession.openBurstOf(AuthorId(1L))?.location)
    }

    @Test
    fun `다른 위치의 버스트를 세션에 넣으면 거부된다`() {
        val loc = BurstLocationKey(ChannelId(100L), null)
        val threadBurst = UtteranceBurst.open(guild, fragment(1, channelId = 100L, threadId = 200L))
        assertThatThrownBy { BurstSession.empty(loc).withOpenBurst(threadBurst) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `withoutAuthor 는 작성자의 OPEN 버스트를 제거한다`() {
        val loc = BurstLocationKey(ChannelId(100L), null)
        val session =
            BurstSession
                .empty(loc)
                .withOpenBurst(UtteranceBurst.open(guild, fragment(1)))
                .withoutAuthor(AuthorId(1L))
        assertNull(session.openBurstOf(AuthorId(1L)))
    }
}
