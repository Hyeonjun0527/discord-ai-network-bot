package com.discordassistant.central.conversation.application.replay

import com.discordassistant.central.conversation.application.dispatch.InMemoryProjectionLedger
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P03-T019 acceptance: 운영 Discord 전송 side effect 가 replay 중 절대 실행되지 않는다.
 *
 * replay 가 의존하는 sink 는 [ReplayProjectionSink] 뿐이며 Discord 전송 포트를 타입 수준에서 참조하지 않는다.
 * 이 테스트는 replay 가 **오직 projection 재적용 sink 만** 호출하고, 같은 범위 재실행이 멱등함을 증명한다.
 */
class ReplayProjectionServiceTest {
    private val criteria =
        ReplayCriteria(
            guildId = GuildId(1L),
            channelId = ChannelId(10L),
            from = Instant.parse("2026-06-21T00:00:00Z"),
            to = Instant.parse("2026-06-22T00:00:00Z"),
            projectionVersion = 2,
        )

    private fun keys(vararg ids: String): ReplayEventSourcePort =
        ReplayEventSourcePort { ids.map { ReplayEventKey(EventId(it), ChannelId(10L)) } }

    @Test
    fun `replay 는 projection sink 만 호출하고 외부 전송을 하지 않는다`() {
        val sinkCalls = mutableListOf<Pair<String, Int>>()
        val sink = ReplayProjectionSink { key, version -> sinkCalls.add(key.eventId.value to version) }
        val service = ReplayProjectionService(keys("a", "b", "c"), sink, InMemoryProjectionLedger())

        val report = service.replay(criteria)

        // sink(=projection 재적용)만 호출됨 — Discord 전송 경로는 타입상 존재하지 않는다.
        assertEquals(listOf("a" to 2, "b" to 2, "c" to 2), sinkCalls)
        assertEquals(ReplayReport(scanned = 3, reapplied = 3, skippedDuplicate = 0, projectionVersion = 2), report)
    }

    @Test
    fun `같은 범위를 두 번 replay 해도 같은 version 은 한 번만 재적용한다 멱등`() {
        val sinkCalls = mutableListOf<String>()
        val sink = ReplayProjectionSink { key, _ -> sinkCalls.add(key.eventId.value) }
        val ledger = InMemoryProjectionLedger()
        val service = ReplayProjectionService(keys("a", "b"), sink, ledger)

        val first = service.replay(criteria)
        val second = service.replay(criteria)

        assertEquals(2, first.reapplied)
        assertEquals(0, second.reapplied, "두 번째는 모두 중복")
        assertEquals(2, second.skippedDuplicate)
        assertEquals(listOf("a", "b"), sinkCalls, "재적용은 한 번만")
    }

    @Test
    fun `빈 범위는 아무것도 재적용하지 않는다`() {
        val sink = ReplayProjectionSink { _, _ -> error("빈 범위에서 sink 가 호출되면 안 된다") }
        val report = ReplayProjectionService(keys(), sink, InMemoryProjectionLedger()).replay(criteria)
        assertEquals(0, report.scanned)
        assertEquals(0, report.reapplied)
    }

    @Test
    fun `from 이 to 이후면 생성 시 거부한다`() {
        val ex =
            runCatching {
                ReplayCriteria(
                    guildId = GuildId(1L),
                    channelId = null,
                    from = Instant.parse("2026-06-22T00:00:00Z"),
                    to = Instant.parse("2026-06-21T00:00:00Z"),
                    projectionVersion = 0,
                )
            }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }
}
