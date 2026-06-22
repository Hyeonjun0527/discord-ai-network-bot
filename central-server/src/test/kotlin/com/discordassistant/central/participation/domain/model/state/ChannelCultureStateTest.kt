package com.discordassistant.central.participation.domain.model.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** NEXA-P06-T003: 봇/옵트아웃 데이터가 통계에서 제외된다 + 범위 가드. */
class ChannelCultureStateTest {
    private val scope = ChannelScope(guildPseudonym = "g#1", channelPseudonym = "c#1")

    @Test
    fun `초기 상태는 표본 없음`() {
        val s = ChannelCultureState.empty(scope)
        assertFalse(s.hasSample)
        assertEquals(0, s.humanBurstCount)
    }

    @Test
    fun `acceptance - includesBotOrOptOut 은 항상 false (봇 옵트아웃 제외 불변식)`() {
        val s =
            ChannelCultureState(
                scope = scope,
                humanBurstCount = 10,
                humanBurstsPerMinute = 2.0,
                averageBurstSize = 1.5,
                averageReplyDelay = Duration.ofSeconds(30),
                reactionRatio = 0.3,
                mentionResponseRatio = 0.8,
            )
        assertFalse(s.includesBotOrOptOut)
        assertTrue(s.hasSample)
    }

    @Test
    fun `범위 밖 비율은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelCultureState(scope = scope, reactionRatio = 1.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChannelCultureState(scope = scope, mentionResponseRatio = -0.1)
        }
    }
}
