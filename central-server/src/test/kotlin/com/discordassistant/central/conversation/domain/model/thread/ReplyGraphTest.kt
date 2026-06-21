package com.discordassistant.central.conversation.domain.model.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.event.MessageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T002: 삭제된 target 도 tombstone node 로 유지해 그래프 연결이 깨지지 않는다. */
class ReplyGraphTest {
    private val burstA = BurstId("burst:A")
    private val burstB = BurstId("burst:B")

    @Test
    fun `해결된 target 으로의 reply edge 는 incoming 으로 찾을 수 있다`() {
        val graph =
            ReplyGraph(
                listOf(
                    ReplyEdge(
                        source = burstA,
                        target = ReplyTarget.Resolved(burstB),
                        viaMessage = MessageId(10L),
                        toMessage = MessageId(5L),
                    ),
                ),
            )
        assertEquals(1, graph.outgoing(burstA).size)
        assertEquals(1, graph.incoming(burstB).size)
    }

    @Test
    fun `삭제된 target 은 tombstone node 로 유지되어 edge 가 보존된다 (acceptance)`() {
        val graph =
            ReplyGraph(
                listOf(
                    ReplyEdge(
                        source = burstA,
                        target = ReplyTarget.Tombstone,
                        viaMessage = MessageId(10L),
                        toMessage = MessageId(999L),
                    ),
                ),
            )
        // edge 는 버려지지 않는다 — source 에서 나가는 연결이 유지된다.
        assertEquals(1, graph.outgoing(burstA).size)
        assertTrue(graph.outgoing(burstA).first().target is ReplyTarget.Tombstone)
        // tombstone 은 해결된 burst 가 아니므로 어떤 burst 의 incoming 에도 잡히지 않는다.
        assertEquals(0, graph.incoming(burstB).size)
        // 삭제됐어도 어떤 메시지를 가리켰는지는 보존된다.
        assertEquals(MessageId(999L), graph.outgoing(burstA).first().toMessage)
    }

    @Test
    fun `빈 그래프는 edge 가 없다`() {
        assertEquals(0, ReplyGraph.EMPTY.edges.size)
    }
}
