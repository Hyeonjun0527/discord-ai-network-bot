package com.discordassistant.central.conversation.domain.model.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** NEXA-P05-T001: 한 Discord 채널 안에서 여러 논리 대화가 같은 channel ID 를 공유한다. */
class ConversationThreadIdTest {
    private val location = BurstLocationKey(channelId = ChannelId(100L), threadId = null)

    @Test
    fun `같은 채널의 서로 다른 논리 대화는 다른 ThreadId 다 (acceptance)`() {
        val first = ConversationThreadId.of(location, ordinal = 0)
        val second = ConversationThreadId.of(location, ordinal = 1)
        assertNotEquals(first, second)
    }

    @Test
    fun `같은 위치 같은 ordinal 은 결정론적으로 같은 id 다`() {
        assertEquals(ConversationThreadId.of(location, 0), ConversationThreadId.of(location, 0))
    }

    @Test
    fun `채널 직속과 스레드 안은 다른 위치라 다른 id 다`() {
        val inThread = BurstLocationKey(channelId = ChannelId(100L), threadId = ChannelId(200L))
        assertNotEquals(ConversationThreadId.of(location, 0), ConversationThreadId.of(inThread, 0))
    }

    @Test
    fun `ordinal 음수는 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { ConversationThreadId.of(location, -1) }
    }

    @Test
    fun `빈 value 는 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { ConversationThreadId("") }
    }
}
