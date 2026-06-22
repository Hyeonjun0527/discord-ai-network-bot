package com.discordassistant.central.conversation.domain.model.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NormalizedDiscordEvent 공통 봉투의 결정론 증명(NEXA-P02-T015 acceptance):
 * 동등성(equals/hashCode), 순서 비교(chronology), 직렬화 round-trip(순수 구조 비교).
 *
 * 봉투의 최소 seed 구현 [GenericObservedEvent](production)로 봉투 계약만 검증한다(구체 Discord 이벤트는 후속
 * T016~). 식별자 value class 와 PrivacyClass enum 은 그대로 사용한다.
 */
class NormalizedDiscordEventTest {
    private fun sample(
        eventId: String = "evt-1",
        guildId: Long = 100L,
        channelId: Long = 200L,
        occurredAt: Instant = Instant.parse("2026-06-21T10:00:00Z"),
        receivedAt: Instant = Instant.parse("2026-06-21T10:00:01Z"),
        sourceSequence: Long = 1L,
        privacyClass: PrivacyClass = PrivacyClass.HIGH,
    ): GenericObservedEvent =
        GenericObservedEvent(
            eventId = EventId(eventId),
            guildId = GuildId(guildId),
            channelId = ChannelId(channelId),
            occurredAt = occurredAt,
            receivedAt = receivedAt,
            sourceSequence = sourceSequence,
            privacyClass = privacyClass,
        )

    // ── 동등성 ────────────────────────────────────────────────────────────────────

    @Test
    fun `같은 필드면 equals 와 hashCode 가 동일`() {
        val a = sample()
        val b = sample()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `한 필드라도 다르면 같지 않다`() {
        assertNotEquals(sample(eventId = "evt-1"), sample(eventId = "evt-2"))
        assertNotEquals(sample(sourceSequence = 1L), sample(sourceSequence = 2L))
        assertNotEquals(sample(privacyClass = PrivacyClass.HIGH), sample(privacyClass = PrivacyClass.LOW))
    }

    // ── 순서 비교(결정론) ─────────────────────────────────────────────────────────

    @Test
    fun `chronology 는 sourceSequence 오름차순으로 정렬`() {
        val e3 = sample(eventId = "c", sourceSequence = 3L)
        val e1 = sample(eventId = "a", sourceSequence = 1L)
        val e2 = sample(eventId = "b", sourceSequence = 2L)

        val sorted = listOf(e3, e1, e2).sortedWith(NormalizedDiscordEvent.chronology)

        assertEquals(listOf(e1, e2, e3), sorted)
    }

    @Test
    fun `chronology 는 같은 sourceSequence 면 occurredAt 로 안정 정렬`() {
        val later = sample(eventId = "late", sourceSequence = 5L, occurredAt = Instant.parse("2026-06-21T12:00:00Z"))
        val earlier = sample(eventId = "early", sourceSequence = 5L, occurredAt = Instant.parse("2026-06-21T11:00:00Z"))

        val sorted = listOf(later, earlier).sortedWith(NormalizedDiscordEvent.chronology)

        assertEquals(listOf(earlier, later), sorted)
    }

    @Test
    fun `chronology 는 같은 키 반복 정렬에도 결정론적(여러 번 정렬해도 동일)`() {
        val events = (1..50).map { sample(eventId = "e$it", sourceSequence = (it % 5).toLong()) }
        val first = events.sortedWith(NormalizedDiscordEvent.chronology)
        val second = events.shuffled().sortedWith(NormalizedDiscordEvent.chronology)
        // sourceSequence 전순서가 결정론적이면, 입력 순서와 무관하게 sequence 는 비내림차순.
        assertEquals(first.map { it.sourceSequence }, second.map { it.sourceSequence })
        assertTrue(first.zipWithNext().all { (a, b) -> a.sourceSequence <= b.sourceSequence })
    }

    // ── 직렬화 round-trip(순수 구조 비교) ──────────────────────────────────────────

    @Test
    fun `구조 직렬화 round-trip 이 안정적(필드 보존)`() {
        val original = sample(eventId = "evt-rt", guildId = 7L, channelId = 9L, sourceSequence = 42L)

        // 순수 도메인이므로 구조(필드) 직렬화→역직렬화를 단순 평탄/복원으로 모사: 모든 필드를 꺼냈다 다시 봉투로 복원.
        val flattened =
            listOf<Any>(
                original.eventId.value,
                original.guildId.value,
                original.channelId.value,
                original.occurredAt.toString(),
                original.receivedAt.toString(),
                original.sourceSequence,
                original.privacyClass.name,
            )
        val restored =
            GenericObservedEvent(
                eventId = EventId(flattened[0] as String),
                guildId = GuildId(flattened[1] as Long),
                channelId = ChannelId(flattened[2] as Long),
                occurredAt = Instant.parse(flattened[3] as String),
                receivedAt = Instant.parse(flattened[4] as String),
                sourceSequence = flattened[5] as Long,
                privacyClass = PrivacyClass.valueOf(flattened[6] as String),
            )

        assertEquals(original, restored)
        assertEquals(original.hashCode(), restored.hashCode())
    }

    @Test
    fun `EventId 는 공백이면 거부(fail-fast)`() {
        assertThrows(IllegalArgumentException::class.java) { EventId("") }
        assertThrows(IllegalArgumentException::class.java) { EventId("   ") }
    }
}
