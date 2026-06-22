package com.discordassistant.central.conversation.domain.model.burst

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageId
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P04-T002 acceptance: OPEN/FINALIZED/CORRECTED 상태 전이가 불법 순서를 거부한다.
 */
class UtteranceBurstTest {
    private val guild = GuildId(1L)
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
    fun `open 은 첫 조각으로 OPEN 버스트를 만들고 id 가 결정론적이다`() {
        val b1 = UtteranceBurst.open(guild, fragment(1))
        val b2 = UtteranceBurst.open(guild, fragment(1))
        assertEquals(BurstStatus.OPEN, b1.status)
        assertEquals(b1.burstId, b2.burstId)
        assertEquals(listOf(MessageId(1)), b1.messageIds)
    }

    @Test
    fun `append 는 시간순 정렬을 유지하고 새 인스턴스를 만든다`() {
        val open = UtteranceBurst.open(guild, fragment(messageId = 1, seq = 1, at = t0))
        val appended = open.append(fragment(messageId = 2, seq = 2, at = t0.plusSeconds(1)))
        assertEquals(listOf(MessageId(1), MessageId(2)), appended.messageIds)
        assertEquals(t0.plusSeconds(1), appended.lastFragmentAt)
        // 불변: 원본은 그대로.
        assertEquals(1, open.fragments.size)
    }

    @Test
    fun `FINALIZED 버스트에 append 하면 불법 전이로 거부된다`() {
        val finalized = UtteranceBurst.open(guild, fragment(1)).finalize()
        assertThatThrownBy { finalized.append(fragment(2)) }
            .isInstanceOf(IllegalBurstTransition::class.java)
    }

    @Test
    fun `이중 finalize 는 불법 전이로 거부된다`() {
        val finalized = UtteranceBurst.open(guild, fragment(1)).finalize()
        assertThatThrownBy { finalized.finalize() }
            .isInstanceOf(IllegalBurstTransition::class.java)
    }

    @Test
    fun `correct 는 FINALIZED 에서만 가능하고 OPEN 이나 CORRECTED 에서는 거부된다`() {
        val open = UtteranceBurst.open(guild, fragment(1))
        assertThatThrownBy { open.correct() }.isInstanceOf(IllegalBurstTransition::class.java)

        val corrected = open.finalize().correct()
        assertEquals(BurstStatus.CORRECTED, corrected.status)
        assertThatThrownBy { corrected.correct() }.isInstanceOf(IllegalBurstTransition::class.java)
        // CORRECTED 후 finalize 도 금지.
        assertThatThrownBy { corrected.finalize() }.isInstanceOf(IllegalBurstTransition::class.java)
    }

    @Test
    fun `다른 작성자 조각은 같은 버스트에 섞일 수 없다`() {
        val open = UtteranceBurst.open(guild, fragment(messageId = 1, authorId = 1L))
        assertThatThrownBy { open.append(fragment(messageId = 2, authorId = 2L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `다른 위치 조각은 같은 버스트에 섞일 수 없다`() {
        val open = UtteranceBurst.open(guild, fragment(messageId = 1, threadId = null))
        assertThatThrownBy { open.append(fragment(messageId = 2, threadId = 999L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
