package com.discordassistant.central.conversation.application.dispatch

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GenericObservedEvent
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P03-T015 acceptance: 고정 Clock fixture 로 경계 시각과 최대 버퍼 크기가 테스트된다.
 */
class OutOfOrderBufferTest {
    private val base = Instant.parse("2026-06-21T10:00:00Z")

    private fun event(
        id: String,
        seq: Long,
        channel: Long = 10L,
        occurred: String = "2026-06-21T10:00:00Z",
    ): NormalizedDiscordEvent =
        GenericObservedEvent(
            eventId = EventId(id),
            guildId = GuildId(1L),
            channelId = ChannelId(channel),
            occurredAt = Instant.parse(occurred),
            receivedAt = base,
            sourceSequence = seq,
            privacyClass = PrivacyClass.LOW,
        )

    private class MutableClock(
        var instant: Instant,
    ) : Clock() {
        override fun instant(): Instant = instant

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }

    @Test
    fun `허용 창 안의 역순 이벤트를 재정렬해 방출한다`() {
        val clock = MutableClock(base)
        val buffer = OutOfOrderBuffer(window = Duration.ofSeconds(5), maxBufferSize = 100, clock = clock)

        // 역순 도착: seq 3, 1, 2.
        assertTrue(buffer.offer(event("s3", seq = 3)).ready.isEmpty(), "창 안이라 아직 대기")
        assertTrue(buffer.offer(event("s1", seq = 1)).ready.isEmpty())
        assertTrue(buffer.offer(event("s2", seq = 2)).ready.isEmpty())

        // 창 만료 시점으로 시계 전진 → 재정렬(1,2,3) 방출.
        clock.instant = base.plusSeconds(5)
        val ready = buffer.poll()
        assertEquals(listOf("s1", "s2", "s3"), ready.map { it.eventId.value })
        assertEquals(0, buffer.bufferedCount())
    }

    @Test
    fun `경계 시각 enqueuedAt 더하기 window 가 now 와 같으면 만료다`() {
        val clock = MutableClock(base)
        val buffer = OutOfOrderBuffer(window = Duration.ofSeconds(5), maxBufferSize = 100, clock = clock)
        buffer.offer(event("e", seq = 1))

        // now = enqueuedAt + window - 1ns → 아직 만료 아님.
        clock.instant = base.plusSeconds(5).minusNanos(1)
        assertTrue(buffer.poll().isEmpty(), "경계 직전은 만료 아님")

        // now = enqueuedAt + window → 만료(<= 조건).
        clock.instant = base.plusSeconds(5)
        assertEquals(listOf("e"), buffer.poll().map { it.eventId.value }, "경계 시각은 만료")
    }

    @Test
    fun `최대 버퍼 크기를 초과하면 가장 이른 순서를 강제 방출한다`() {
        val clock = MutableClock(base)
        val buffer = OutOfOrderBuffer(window = Duration.ofMinutes(10), maxBufferSize = 2, clock = clock)

        assertTrue(buffer.offer(event("s2", seq = 2)).ready.isEmpty())
        assertTrue(buffer.offer(event("s3", seq = 3)).ready.isEmpty())
        // 3번째 → maxBufferSize=2 초과 → 가장 이른(seq 최소) 강제 방출. seq 1 추가 후 head=s1 방출.
        val result = buffer.offer(event("s1", seq = 1))
        assertEquals(listOf("s1"), result.ready.map { it.eventId.value }, "버퍼 초과 시 최소 순서 강제 방출")
        assertEquals(2, buffer.bufferedCount())
    }

    @Test
    fun `이미 방출된 순서보다 이른 이벤트는 late 로 표시한다`() {
        val clock = MutableClock(base)
        val buffer = OutOfOrderBuffer(window = Duration.ofSeconds(1), maxBufferSize = 100, clock = clock)
        buffer.offer(event("s5", seq = 5))
        clock.instant = base.plusSeconds(1)
        buffer.poll() // s5 방출 → lastEmitted = 5.

        // 이제 seq 3 도착 — 이미 지나간 순서라 late.
        val result = buffer.offer(event("s3", seq = 3))
        assertTrue(result.late)
        assertEquals("s3", result.lateEvent?.eventId?.value)
        assertTrue(result.ready.isEmpty())
        assertEquals(0, buffer.bufferedCount(), "late 는 버퍼에 적재하지 않는다")
    }

    @Test
    fun `파티션별로 버퍼가 격리된다`() {
        val clock = MutableClock(base)
        val buffer = OutOfOrderBuffer(window = Duration.ofSeconds(5), maxBufferSize = 100, clock = clock)
        buffer.offer(event("c1", seq = 1, channel = 1))
        buffer.offer(event("c2", seq = 1, channel = 2))
        assertEquals(2, buffer.bufferedCount(), "두 채널이 각각 적체")

        // 창 만료 후 두 파티션 모두 방출.
        clock.instant = base.plusSeconds(5)
        val ready = buffer.poll().map { it.eventId.value }.toSet()
        assertEquals(setOf("c1", "c2"), ready)
        assertFalse(ready.isEmpty())
    }
}
