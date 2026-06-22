package com.discordassistant.central.conversation.domain.service.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** NEXA-P05-T004: 미래 burst 비참조, 설정 최대 창 밖 edge 금지. */
class TemporalAdjacencyTest {
    private val guild = GuildId(1L)
    private val t0 = BurstTestFragments.T0
    private val config = TemporalAdjacencyConfig(maxWindow = Duration.ofMinutes(5))

    private fun burst(
        id: Long,
        at: java.time.Instant,
        author: Long = 1L,
        channel: Long = 100L,
    ) = UtteranceBurst.open(guild, BurstTestFragments.fragment(id, authorId = author, at = at, channelId = channel))

    @Test
    fun `최대 창 안의 가까운 발화는 양의 점수다`() {
        val earlier = burst(1, t0, author = 1L)
        val later = burst(2, t0.plusSeconds(30), author = 2L)
        assertTrue(TemporalAdjacency.score(earlier, later, config) > 0.0)
    }

    @Test
    fun `최대 창 밖이면 점수 0 이고 edge 를 만들지 않는다 (acceptance)`() {
        val earlier = burst(1, t0, author = 1L)
        val later = burst(2, t0.plusSeconds(301), author = 2L)
        assertEquals(0.0, TemporalAdjacency.score(earlier, later, config))
        assertNull(TemporalAdjacency.edgeOrNull(earlier, later, config))
    }

    @Test
    fun `later 가 earlier 보다 과거면 거부한다 (미래 비참조 acceptance)`() {
        val earlier = burst(1, t0.plusSeconds(60), author = 1L)
        val later = burst(2, t0, author = 2L)
        assertThrows(IllegalArgumentException::class.java) {
            TemporalAdjacency.score(earlier, later, config)
        }
    }

    @Test
    fun `다른 위치면 점수 0 이다`() {
        val earlier = burst(1, t0, channel = 100L)
        val later = burst(2, t0.plusSeconds(10), channel = 200L)
        assertEquals(0.0, TemporalAdjacency.score(earlier, later, config))
    }

    @Test
    fun `화자 교대가 같은 작성자 연속보다 점수가 높다`() {
        val earlier = burst(1, t0, author = 1L)
        val alternating = burst(2, t0.plusSeconds(10), author = 2L)
        val sameAuthor = burst(3, t0.plusSeconds(10), author = 1L)
        assertTrue(
            TemporalAdjacency.score(earlier, alternating, config) >
                TemporalAdjacency.score(earlier, sameAuthor, config),
        )
    }
}
