package com.discordassistant.central.conversation.domain.event

import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * NEXA-P04-T017 acceptance: 동일 버스트에 finalize 이벤트가 한 번만 발행된다 — domain-events.md 계약
 * (발행자=conversation, 멱등키=burstId, PII medium=식별자만). 종료 버스트·fragment IDs·segmentation version·종료 이유 운반.
 */
class BurstFinalizedTest {
    private val guild = GuildId(1L)

    private fun finalizedBurst(): UtteranceBurst =
        UtteranceBurst
            .open(guild, BurstTestFragments.fragment(1, seq = 1))
            .append(BurstTestFragments.fragment(2, seq = 2, at = BurstTestFragments.T0.plusSeconds(1)))
            .finalize()

    @Test
    fun `최종 버스트·fragment IDs·segmentation version·종료 이유를 운반한다`() {
        val burst = finalizedBurst()
        val event =
            BurstFinalized.fromBurst(
                burst,
                segmentationVersion = 4,
                terminationReason = BurstTerminationReason.GAP_ELAPSED,
            )
        assertEquals(burst.burstId, event.burstId, "멱등키 = burstId")
        assertEquals(listOf(MessageId(1), MessageId(2)), event.fragmentIds)
        assertEquals(4, event.segmentationVersion)
        assertEquals(BurstTerminationReason.GAP_ELAPSED, event.terminationReason)
    }

    @Test
    fun `멱등키가 burstId 라 같은 버스트는 같은 이벤트 식별로 수렴한다`() {
        val burst = finalizedBurst()
        val a = BurstFinalized.fromBurst(burst, 1, BurstTerminationReason.STREAM_END)
        val b = BurstFinalized.fromBurst(burst, 1, BurstTerminationReason.STREAM_END)
        // 같은 버스트 → 같은 멱등키 → data class equals 동일(소비자가 중복 수신을 무시할 근거).
        assertEquals(a, b)
        assertEquals(a.burstId, b.burstId)
    }

    @Test
    fun `FINALIZED 가 아닌 버스트로는 발행할 수 없다 (버스트당 1회 불변식)`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1))
        assertThrows(IllegalArgumentException::class.java) {
            BurstFinalized.fromBurst(open, 1, BurstTerminationReason.STREAM_END)
        }
    }
}
