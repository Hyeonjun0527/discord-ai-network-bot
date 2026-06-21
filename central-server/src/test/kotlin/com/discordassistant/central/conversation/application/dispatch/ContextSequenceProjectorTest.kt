package com.discordassistant.central.conversation.application.dispatch

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GenericObservedEvent
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P03-T014 acceptance: 재시작 후 sequence 가 역행하지 않고 충돌(같은 sourceSequence) 시 결정 규칙이 테스트된다.
 */
class ContextSequenceProjectorTest {
    private fun event(
        id: String,
        channel: Long = 10L,
        seq: Long,
        occurred: String = "2026-06-21T10:00:00Z",
    ): NormalizedDiscordEvent =
        GenericObservedEvent(
            eventId = EventId(id),
            guildId = GuildId(1L),
            channelId = ChannelId(channel),
            occurredAt = Instant.parse(occurred),
            receivedAt = Instant.parse("2026-06-21T10:00:01Z"),
            sourceSequence = seq,
            privacyClass = PrivacyClass.LOW,
        )

    @Test
    fun `같은 파티션 안에서 단조 증가 번호를 부여한다`() {
        val projector = ContextSequenceProjector()
        assertEquals(1L, projector.assign(event("a", seq = 1)).contextSequence)
        assertEquals(2L, projector.assign(event("b", seq = 2)).contextSequence)
        assertEquals(3L, projector.assign(event("c", seq = 3)).contextSequence)
    }

    @Test
    fun `파티션별로 독립적으로 번호를 센다`() {
        val projector = ContextSequenceProjector()
        assertEquals(1L, projector.assign(event("c1", channel = 1, seq = 1)).contextSequence)
        assertEquals(1L, projector.assign(event("c2", channel = 2, seq = 1)).contextSequence)
        assertEquals(2L, projector.assign(event("c1b", channel = 1, seq = 2)).contextSequence)
    }

    @Test
    fun `재시작 복원 후 sequence 가 역행하지 않는다`() {
        val partition = ChannelPartitionKey.of(ChannelId(10))
        val projector = ContextSequenceProjector()
        // 재시작 전 마지막으로 부여된 번호가 100 이었다고 복원.
        projector.resumeFrom(partition, lastAssigned = 100)

        val assigned = projector.assign(event("after-restart", seq = 5))
        assertEquals(101L, assigned.contextSequence, "복원값+1 — 역행 없음")
        assertTrue(assigned.contextSequence > 100)
    }

    @Test
    fun `복원은 high-water mark 를 낮추지 않는다`() {
        val partition = ChannelPartitionKey.of(ChannelId(10))
        val projector = ContextSequenceProjector()
        projector.assign(event("a", seq = 1)) // mark = 1
        projector.assign(event("b", seq = 2)) // mark = 2
        // 더 낮은 값으로 복원 시도 — 무시(역행 금지).
        projector.resumeFrom(partition, lastAssigned = 0)
        assertEquals(3L, projector.assign(event("c", seq = 3)).contextSequence)
    }

    @Test
    fun `충돌 같은 sourceSequence 은 chronology 전순서로 결정론적으로 번호를 매긴다`() {
        // 같은 sourceSequence=5, occurredAt 으로 타이브레이크. 입력 순서를 뒤섞어도 같은 배정.
        val e1 = event("z", seq = 5, occurred = "2026-06-21T10:00:02Z")
        val e2 = event("a", seq = 5, occurred = "2026-06-21T10:00:01Z")
        val e3 = event("m", seq = 5, occurred = "2026-06-21T10:00:01Z") // e2 와 동시각 → eventId 타이브레이크

        val first = ContextSequenceProjector().assignBatch(listOf(e1, e2, e3))
        val second = ContextSequenceProjector().assignBatch(listOf(e3, e1, e2)) // 입력 순서 뒤섞음

        val firstOrder = first.map { it.event.eventId.value to it.contextSequence }
        val secondOrder = second.map { it.event.eventId.value to it.contextSequence }
        assertEquals(firstOrder, secondOrder, "입력 순서와 무관하게 같은 배정")
        // occurredAt 이른 a(1), 동시각 m(2, eventId 타이브레이크), 늦은 z(3).
        assertEquals(listOf("a" to 1L, "m" to 2L, "z" to 3L), firstOrder)
    }
}
