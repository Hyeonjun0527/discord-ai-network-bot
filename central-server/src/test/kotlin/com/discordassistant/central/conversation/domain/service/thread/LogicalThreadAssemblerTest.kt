package com.discordassistant.central.conversation.domain.service.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageId
import com.discordassistant.central.conversation.domain.model.thread.ReplyEdge
import com.discordassistant.central.conversation.domain.model.thread.ReplyGraph
import com.discordassistant.central.conversation.domain.model.thread.ReplyTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/** NEXA-P05-T010: 동시에 진행되는 두 대화 fixture 가 하나로 합쳐지지 않는다. */
class LogicalThreadAssemblerTest {
    private val guild = GuildId(1L)
    private val t0 = BurstTestFragments.T0

    private fun burst(
        id: Long,
        author: Long,
        at: java.time.Instant,
    ) = UtteranceBurst.open(guild, BurstTestFragments.fragment(id, authorId = author, at = at))

    @Test
    fun `reply 로 이어진 두 burst 는 같은 논리 스레드다`() {
        val a = burst(1, author = 1L, at = t0)
        val b = burst(2, author = 2L, at = t0.plusSeconds(5))
        val replyGraph =
            ReplyGraph(
                listOf(
                    ReplyEdge(
                        source = b.burstId,
                        target = ReplyTarget.Resolved(a.burstId),
                        viaMessage = MessageId(2L),
                        toMessage = MessageId(1L),
                    ),
                ),
            )
        val result = LogicalThreadAssembler.assemble(listOf(a, b), replyGraph = replyGraph)
        assertEquals(result.getValue(a.burstId), result.getValue(b.burstId))
    }

    @Test
    fun `교차 신호가 없는 두 동시 대화는 서로 다른 스레드다 (acceptance)`() {
        // 대화 X: burst 1↔3 (reply 로 연결). 대화 Y: burst 2↔4 (reply 로 연결). 두 대화는 서로 참조 안 함.
        val x1 = burst(1, author = 1L, at = t0)
        val y1 = burst(2, author = 2L, at = t0.plusSeconds(1))
        val x2 = burst(3, author = 3L, at = t0.plusSeconds(2))
        val y2 = burst(4, author = 4L, at = t0.plusSeconds(3))
        val replyGraph =
            ReplyGraph(
                listOf(
                    ReplyEdge(x2.burstId, ReplyTarget.Resolved(x1.burstId), MessageId(3L), MessageId(1L)),
                    ReplyEdge(y2.burstId, ReplyTarget.Resolved(y1.burstId), MessageId(4L), MessageId(2L)),
                ),
            )
        val result = LogicalThreadAssembler.assemble(listOf(x1, y1, x2, y2), replyGraph = replyGraph)
        // 대화 X 끼리는 같은 스레드.
        assertEquals(result.getValue(x1.burstId), result.getValue(x2.burstId))
        // 대화 Y 끼리는 같은 스레드.
        assertEquals(result.getValue(y1.burstId), result.getValue(y2.burstId))
        // 두 대화는 합쳐지지 않는다 — 서로 다른 스레드.
        assertNotEquals(result.getValue(x1.burstId), result.getValue(y1.burstId))
    }

    @Test
    fun `tombstone reply target 은 스레드를 병합하지 않는다`() {
        val a = burst(1, author = 1L, at = t0)
        val b = burst(2, author = 2L, at = t0.plusSeconds(5))
        val replyGraph =
            ReplyGraph(
                listOf(
                    ReplyEdge(b.burstId, ReplyTarget.Tombstone, MessageId(2L), MessageId(999L)),
                ),
            )
        val result = LogicalThreadAssembler.assemble(listOf(a, b), replyGraph = replyGraph)
        // 삭제된 대상으로의 reply 는 a 와 병합하지 않는다(연결할 burst 가 없음).
        assertNotEquals(result.getValue(a.burstId), result.getValue(b.burstId))
    }

    @Test
    fun `mention 보조 링크로도 병합된다`() {
        val a = burst(1, author = 1L, at = t0)
        val b = burst(2, author = 2L, at = t0.plusSeconds(5))
        val result =
            LogicalThreadAssembler.assemble(
                listOf(a, b),
                mentionLinks = listOf(a.burstId to b.burstId),
            )
        assertEquals(result.getValue(a.burstId), result.getValue(b.burstId))
    }

    @Test
    fun `adjacency 보조 edge 로도 병합된다`() {
        val a = burst(1, author = 1L, at = t0)
        val b = burst(2, author = 2L, at = t0.plusSeconds(5))
        val edge = TemporalAdjacency.edgeOrNull(a, b, TemporalAdjacencyConfig(maxWindow = Duration.ofMinutes(5)))
        val result =
            LogicalThreadAssembler.assemble(
                listOf(a, b),
                adjacencyEdges = listOfNotNull(edge),
            )
        assertEquals(result.getValue(a.burstId), result.getValue(b.burstId))
    }
}
