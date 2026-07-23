package com.discordassistant.central.conversation.domain.service.rawcontext

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextRetentionPolicy
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextUnavailableReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RawContextRingBufferTest {
    private val scope = RawContextScope(guildId = 1L, channelId = 2L, threadId = 3L)
    private val t0 = Instant.parse("2026-06-29T00:00:00Z")

    @Test
    fun `기본 raw context 보존 예산은 20만자다`() {
        assertEquals(200_000, RawContextRetentionPolicy().maxRawChars)
        assertEquals(2_000, RawContextRetentionPolicy().maxEntries)
    }

    @Test
    fun `raw char 한도를 넘으면 가장 오래된 원문부터 제거한다`() {
        val buffer = RawContextRingBuffer(scope, RawContextRetentionPolicy(maxRawChars = 8))

        buffer.append(entry(1L, "1234", t0.plusSeconds(1)))
        buffer.append(entry(2L, "5678", t0.plusSeconds(2)))
        val result = buffer.append(entry(3L, "abcd", t0.plusSeconds(3)))

        assertEquals(listOf(1L), result.evictedMessageIds)
        assertEquals(listOf(2L, 3L), result.snapshot.entries.map { it.messageId })
        assertEquals(8, result.snapshot.retainedRawChars)
    }

    @Test
    fun `unavailable content 는 원문 길이로 계산하지 않고 빈 문자열과 구분된다`() {
        val buffer = RawContextRingBuffer(scope, RawContextRetentionPolicy(maxRawChars = 4))

        buffer.append(entry(1L, "1234", t0.plusSeconds(1)))
        val unavailable =
            RawContextEntry(
                scope = scope,
                messageId = 2L,
                authorPseudonym = "user_b",
                occurredAt = t0.plusSeconds(2),
                replyToMessageId = null,
                sourceType = RawContextSourceType.HUMAN,
                content = RawContextContent.Unavailable(RawContextUnavailableReason.INTENT_MISSING),
            )
        val result = buffer.append(unavailable)

        assertEquals(emptyList<Long>(), result.evictedMessageIds)
        assertEquals(4, result.snapshot.retainedRawChars)
        val lastContent =
            result.snapshot
                .entries
                .last()
                .content
        assertTrue(lastContent is RawContextContent.Unavailable)
    }

    @Test
    fun `문자 수가 0인 unavailable 메시지도 entry 상한을 넘어 무제한 쌓이지 않는다`() {
        val buffer = RawContextRingBuffer(scope, RawContextRetentionPolicy(maxRawChars = 100, maxEntries = 2))

        fun unavailable(messageId: Long) =
            RawContextEntry(
                scope = scope,
                messageId = messageId,
                authorPseudonym = "user_b",
                occurredAt = t0.plusSeconds(messageId),
                replyToMessageId = null,
                sourceType = RawContextSourceType.HUMAN,
                content = RawContextContent.Unavailable(RawContextUnavailableReason.EMPTY),
            )

        buffer.append(unavailable(1L))
        buffer.append(unavailable(2L))
        val result = buffer.append(unavailable(3L))

        assertEquals(listOf(1L), result.evictedMessageIds)
        assertEquals(listOf(2L, 3L), result.snapshot.entries.map { it.messageId })
    }

    @Test
    fun `redaction remove 는 지정 messageId 원문을 context 에서 제거한다`() {
        val buffer = RawContextRingBuffer(scope, RawContextRetentionPolicy(maxRawChars = 12))
        buffer.append(entry(1L, "1234", t0.plusSeconds(1)))
        buffer.append(entry(2L, "5678", t0.plusSeconds(2)))

        val result = buffer.remove(1L)

        assertTrue(result.removed)
        assertEquals(listOf(2L), result.snapshot.entries.map { it.messageId })
        assertFalse(buffer.remove(1L).removed)
    }

    @Test
    fun `buffer scope 와 다른 entry 는 fail fast 한다`() {
        val buffer = RawContextRingBuffer(scope, RawContextRetentionPolicy(maxRawChars = 8))
        val otherScope = RawContextScope(guildId = 10L, channelId = 20L)

        assertThrows(IllegalArgumentException::class.java) {
            buffer.append(entry(1L, "1234", t0, otherScope))
        }
    }

    private fun entry(
        messageId: Long,
        text: String,
        occurredAt: Instant,
        entryScope: RawContextScope = scope,
    ): RawContextEntry =
        RawContextEntry(
            scope = entryScope,
            messageId = messageId,
            authorPseudonym = "user_a",
            occurredAt = occurredAt,
            replyToMessageId = null,
            sourceType = RawContextSourceType.HUMAN,
            content = RawContextContent.Available(text),
        )
}
