package com.discordassistant.central.conversation.domain.model.burst

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageId
import java.time.Instant

/** P04 burst 테스트 공용 [MessageFragment] 팩토리(테스트 전용). 결정론적 기본값으로 보일러플레이트를 줄인다. */
object BurstTestFragments {
    val T0: Instant = Instant.parse("2026-01-01T11:15:01Z")

    fun fragment(
        messageId: Long,
        authorId: Long = 1L,
        seq: Long = messageId,
        at: Instant = T0,
        channelId: Long = 100L,
        threadId: Long? = null,
        replyTo: Long? = null,
        type: FragmentType = FragmentType.NORMAL,
        content: MessageContent = MessageContent.Available("text-$messageId"),
    ): MessageFragment =
        MessageFragment(
            messageId = MessageId(messageId),
            authorId = AuthorId(authorId),
            channelId = ChannelId(channelId),
            sourceSequence = seq,
            occurredAt = at,
            content = content,
            replyTo = replyTo?.let { MessageId(it) },
            threadId = threadId?.let { ChannelId(it) },
            type = type,
        )
}
