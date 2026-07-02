package com.discordassistant.central.conversation

import com.discordassistant.central.conversation.adapter.inbound.web.ConversationProjectionOpsController
import com.discordassistant.central.conversation.adapter.inbound.web.ConversationProjectionOpsController.ClearDeadLetterResponse
import com.discordassistant.central.conversation.adapter.inbound.web.ConversationProjectionOpsController.ClearReq
import com.discordassistant.central.conversation.adapter.inbound.web.ConversationProjectionOpsController.ReplayReq
import com.discordassistant.central.conversation.application.dispatch.InMemoryProjectionLedger
import com.discordassistant.central.conversation.application.replay.ReplayEventSourcePort
import com.discordassistant.central.conversation.application.replay.ReplayProjectionService
import com.discordassistant.central.conversation.application.replay.ReplayProjectionSink
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.global.error.InvalidRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConversationProjectionOpsControllerTest {
    @Test
    fun `clear dead-letter returns typed result and remains idempotent`() {
        val ledger = InMemoryProjectionLedger()
        ledger.deadLetter(EventId("evt-1"), projectionVersion = 1, reasonCode = "SAFE_TEST", detail = "metadata-only")
        val controller = controller(ledger)

        assertEquals(ClearDeadLetterResponse(cleared = true), controller.clearDeadLetter(ClearReq("evt-1")))
        assertEquals(ClearDeadLetterResponse(cleared = false), controller.clearDeadLetter(ClearReq("evt-1")))
    }

    @Test
    fun `blank dead-letter event id is a domain invalid request`() {
        val ex =
            assertThrows(InvalidRequestException::class.java) {
                controller().clearDeadLetter(ClearReq(" "))
            }

        assertEquals("eventId 는 비어 있을 수 없습니다", ex.message)
    }

    @Test
    fun `replay request validation uses domain invalid request`() {
        assertThrows(InvalidRequestException::class.java) {
            controller().replay(validReplayReq().copy(guildId = 0))
        }
        assertThrows(InvalidRequestException::class.java) {
            controller().replay(validReplayReq().copy(projectionVersion = -1))
        }
        assertThrows(InvalidRequestException::class.java) {
            controller().replay(validReplayReq().copy(from = "not-an-instant"))
        }
        assertThrows(InvalidRequestException::class.java) {
            controller().replay(
                validReplayReq().copy(
                    from = "2026-06-22T00:00:00Z",
                    to = "2026-06-21T00:00:00Z",
                ),
            )
        }
    }

    private fun controller(ledger: InMemoryProjectionLedger = InMemoryProjectionLedger()): ConversationProjectionOpsController =
        ConversationProjectionOpsController(
            ledger = ledger,
            replayService =
                ReplayProjectionService(
                    source = ReplayEventSourcePort { emptyList() },
                    sink = ReplayProjectionSink { _, _ -> },
                    ledger = ledger,
                ),
        )

    private fun validReplayReq(): ReplayReq =
        ReplayReq(
            guildId = 1,
            channelId = 10,
            from = "2026-06-21T00:00:00Z",
            to = "2026-06-22T00:00:00Z",
            projectionVersion = 2,
        )
}
