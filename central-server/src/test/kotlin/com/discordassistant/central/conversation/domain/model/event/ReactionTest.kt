package com.discordassistant.central.conversation.domain.model.event

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P02-T019 Reaction acceptance:
 * 일반/버스트 리액션 차이를 보존하고, 중복 변경을 안전하게 판정한다(idempotent).
 */
class ReactionTest {
    private fun reaction(
        eventId: String = "evt-rx-1",
        messageId: Long = 10L,
        actorId: Long = 20L,
        emoji: EmojiIdentity = EmojiIdentity.Unicode("👍"),
        change: ReactionChange = ReactionChange.ADDED,
        intensity: ReactionIntensity = ReactionIntensity.SINGLE,
    ): Reaction =
        Reaction(
            eventId = EventId(eventId),
            guildId = GuildId(1L),
            channelId = ChannelId(2L),
            occurredAt = Instant.parse("2026-06-21T10:00:00Z"),
            receivedAt = Instant.parse("2026-06-21T10:00:01Z"),
            sourceSequence = 1L,
            privacyClass = PrivacyClass.LOW,
            messageId = MessageId(messageId),
            actorId = AuthorId(actorId),
            emoji = emoji,
            change = change,
            intensity = intensity,
        )

    @Test
    fun `일반과 버스트 리액션의 차이가 보존된다`() {
        val single = reaction(intensity = ReactionIntensity.SINGLE)
        val burst = reaction(intensity = ReactionIntensity.BURST)
        assertNotEquals(single.intensity, burst.intensity)
    }

    @Test
    fun `같은 메시지 이모지 actor 방향이면 중복 변경으로 판정한다 (intensity 무관, idempotent)`() {
        val a = reaction(eventId = "evt-a", intensity = ReactionIntensity.SINGLE)
        val b = reaction(eventId = "evt-b", intensity = ReactionIntensity.BURST)
        // eventId/intensity 가 달라도 같은 변경 — 중복 수신으로 안전 처리.
        assertTrue(a.isSameChangeAs(b))
    }

    @Test
    fun `추가와 삭제는 다른 변경이다`() {
        val added = reaction(change = ReactionChange.ADDED)
        val removed = reaction(change = ReactionChange.REMOVED)
        assertFalse(added.isSameChangeAs(removed))
    }

    @Test
    fun `다른 이모지면 다른 변경이다`() {
        val thumbs = reaction(emoji = EmojiIdentity.Unicode("👍"))
        val custom = reaction(emoji = EmojiIdentity.Custom(customEmojiId = 999L, name = "nia"))
        assertFalse(thumbs.isSameChangeAs(custom))
    }
}
