package com.discordassistant.central.conversation.domain.model.event

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P02-T022 ConsentDenied acceptance:
 * 동의 철회가 일반 메시지 이벤트보다 우선 적용되는 규칙(우선순위/타임스탬프)을 검증한다.
 */
class ConsentDeniedTest {
    private fun denied(
        occurredAt: Instant = Instant.parse("2026-06-21T10:00:00Z"),
        scope: ConsentDenialScope = ConsentDenialScope.USER_OPT_OUT,
        reason: ConsentDenialReason = ConsentDenialReason.USER_REQUESTED,
    ): ConsentDenied =
        ConsentDenied(
            eventId = EventId("evt-cd-1"),
            guildId = GuildId(1L),
            channelId = ChannelId(2L),
            occurredAt = occurredAt,
            receivedAt = occurredAt,
            sourceSequence = 1L,
            privacyClass = PrivacyClass.LOW,
            scope = scope,
            reason = reason,
        )

    private fun message(occurredAt: Instant): MessageCreated =
        MessageCreated(
            eventId = EventId("evt-msg-1"),
            guildId = GuildId(1L),
            channelId = ChannelId(2L),
            occurredAt = occurredAt,
            receivedAt = occurredAt,
            sourceSequence = 2L,
            privacyClass = PrivacyClass.HIGH,
            messageId = MessageId(10L),
            authorId = AuthorId(20L),
            content = MessageContent.Available("hi"),
            replyTo = null,
            mentions = emptySet(),
            attachments = emptyList(),
            threadId = null,
        )

    @Test
    fun `동시각에 도착한 일반 메시지보다 동의 거부가 우선 적용된다`() {
        val at = Instant.parse("2026-06-21T10:00:00Z")
        val deny = denied(occurredAt = at)
        val msg = message(occurredAt = at)
        // 같은 occurredAt 이면 거부가 우선(fail-closed) — 예약된 행동/projection 즉시 중단.
        assertTrue(deny.takesPrecedenceOver(msg))
    }

    @Test
    fun `더 이른 동의 거부는 이후 메시지보다 우선한다`() {
        val deny = denied(occurredAt = Instant.parse("2026-06-21T10:00:00Z"))
        val laterMsg = message(occurredAt = Instant.parse("2026-06-21T10:05:00Z"))
        assertTrue(deny.takesPrecedenceOver(laterMsg))
    }

    @Test
    fun `거부보다 먼저 발생한 메시지에는 우선하지 않는다`() {
        val deny = denied(occurredAt = Instant.parse("2026-06-21T10:05:00Z"))
        val earlierMsg = message(occurredAt = Instant.parse("2026-06-21T10:00:00Z"))
        assertFalse(deny.takesPrecedenceOver(earlierMsg))
    }

    @Test
    fun `우선순위 키는 일반 관찰보다 앞선다`() {
        // 거부의 precedenceKey 는 0 — 정렬에서 일반 이벤트(>=1 규칙)보다 앞선다.
        assertTrue(denied().precedenceKey == 0)
    }
}
