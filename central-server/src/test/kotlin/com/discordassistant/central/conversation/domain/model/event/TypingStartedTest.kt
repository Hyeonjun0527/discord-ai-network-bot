package com.discordassistant.central.conversation.domain.model.event

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P02-T020 TypingStarted acceptance:
 * typing 을 메시지 내용이나 응답 의무로 오해하지 않도록 한다 — 타이핑은 만료될 수 있고 응답을 보장하지 않는다.
 */
class TypingStartedTest {
    private val started = Instant.parse("2026-06-21T10:00:00Z")
    private val expires = Instant.parse("2026-06-21T10:00:10Z")

    private fun typing(): TypingStarted =
        TypingStarted(
            eventId = EventId("evt-ty-1"),
            guildId = GuildId(1L),
            channelId = ChannelId(2L),
            occurredAt = started,
            receivedAt = started,
            sourceSequence = 1L,
            privacyClass = PrivacyClass.LOW,
            actorId = AuthorId(20L),
            startedAt = started,
            expiresAt = expires,
        )

    @Test
    fun `typing 은 원문 텍스트를 운반하지 않는다 (메시지 내용 아님, PII LOW)`() {
        val event = typing()
        // 타입에 content 필드가 없다 — 메시지 내용으로 오해할 수 없음. 등급도 LOW.
        assertTrue(event.privacyClass == PrivacyClass.LOW)
    }

    @Test
    fun `만료 시각 이후엔 곧 메시지가 온다는 근거가 되지 못한다 (응답 의무 아님)`() {
        val event = typing()
        assertFalse(event.isExpiredAt(Instant.parse("2026-06-21T10:00:05Z")))
        assertTrue(event.isExpiredAt(Instant.parse("2026-06-21T10:00:10Z")))
        assertTrue(event.isExpiredAt(Instant.parse("2026-06-21T10:00:11Z")))
    }
}
