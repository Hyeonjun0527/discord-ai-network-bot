package com.discordassistant.central.conversation.domain.model.scene

import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P05-T015: 조용한 채널과 빠른 난장판 fixture 의 템포 값이 구분된다. */
class ConversationTempoTest {
    private val guild = GuildId(1L)
    private val t0 = BurstTestFragments.T0

    private fun burst(
        id: Long,
        author: Long,
        at: Instant,
    ): UtteranceBurst = UtteranceBurst.open(guild, BurstTestFragments.fragment(id, authorId = author, seq = id, at = at))

    @Test
    fun `빈 장면이면 IDLE`() {
        assertEquals(ConversationTempo.IDLE, ConversationTempo.from(emptyList()))
        assertNull(ConversationTempo.IDLE.medianGap)
    }

    @Test
    fun `조용한 채널 — 긴 gap, 1명, overlap 0`() {
        val quiet =
            listOf(
                burst(1, author = 1L, at = t0),
                burst(2, author = 1L, at = t0.plusSeconds(600)), // 10분 뒤.
            )
        val tempo = ConversationTempo.from(quiet)
        assertEquals(1, tempo.activeSpeakerCount)
        assertEquals(0.0, tempo.overlapRatio)
        assertTrue(tempo.burstsPerMinute < 1.0, "조용하면 분당 burst < 1")
    }

    @Test
    fun `빠른 난장판 — 짧은 gap, 여러 명, 분당 burst 높음`() {
        val chaos =
            listOf(
                burst(1, author = 1L, at = t0),
                burst(2, author = 2L, at = t0.plusSeconds(2)),
                burst(3, author = 3L, at = t0.plusSeconds(4)),
                burst(4, author = 1L, at = t0.plusSeconds(6)),
            )
        val tempo = ConversationTempo.from(chaos)
        assertEquals(3, tempo.activeSpeakerCount)
        assertTrue(tempo.burstsPerMinute > 10.0, "난장판이면 분당 burst 높음")
    }

    @Test
    fun `조용한 채널과 난장판의 템포가 뚜렷이 구분된다 (acceptance)`() {
        val quiet = ConversationTempo.from(listOf(burst(1, 1L, t0), burst(2, 1L, t0.plusSeconds(600))))
        val chaos =
            ConversationTempo.from(
                listOf(burst(1, 1L, t0), burst(2, 2L, t0.plusSeconds(1)), burst(3, 3L, t0.plusSeconds(2))),
            )
        assertTrue(chaos.burstsPerMinute > quiet.burstsPerMinute)
        assertTrue(chaos.activeSpeakerCount > quiet.activeSpeakerCount)
        assertTrue(chaos.medianGap!! < quiet.medianGap!!)
    }
}
