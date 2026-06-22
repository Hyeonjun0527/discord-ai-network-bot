package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P04-T013 acceptance: 봇 메시지가 인간 평균 버스트 길이를 오염시키지 않는다 — HUMAN 만 학습 통계에 포함된다.
 */
class BurstActorClassifierTest {
    private val guild = GuildId(1L)

    private fun burst(
        messageId: Long,
        authorId: Long,
        fragments: Int,
    ): UtteranceBurst {
        var b = UtteranceBurst.open(guild, BurstTestFragments.fragment(messageId, authorId = authorId, seq = messageId))
        for (i in 1 until fragments) {
            b = b.append(BurstTestFragments.fragment(messageId * 100 + i, authorId = authorId, seq = messageId * 100 + i))
        }
        return b
    }

    @Test
    fun `HUMAN 만 학습 통계에 포함된다`() {
        assertTrue(BurstActorClassifier.includeInHumanLearning(BurstActorKind.HUMAN))
        assertFalse(BurstActorClassifier.includeInHumanLearning(BurstActorKind.NEXA))
        assertFalse(BurstActorClassifier.includeInHumanLearning(BurstActorKind.OTHER_BOT))
        assertFalse(BurstActorClassifier.includeInHumanLearning(BurstActorKind.WEBHOOK))
    }

    @Test
    fun `봇·webhook 버스트는 인간 평균 버스트 길이를 오염시키지 않는다`() {
        // 인간 2개(길이 2, 4) + 봇 1개(길이 100) + webhook 1개(길이 50).
        val human1 = burst(1, authorId = 11, fragments = 2)
        val human2 = burst(2, authorId = 12, fragments = 4)
        val bot = burst(3, authorId = 99, fragments = 100)
        val webhook = burst(4, authorId = 98, fragments = 50)

        val kindOf: (UtteranceBurst) -> BurstActorKind = {
            when (it.authorId.value) {
                99L -> BurstActorKind.OTHER_BOT
                98L -> BurstActorKind.WEBHOOK
                else -> BurstActorKind.HUMAN
            }
        }

        val humanOnly = BurstActorClassifier.retainHumanBursts(listOf(human1, human2, bot, webhook), kindOf)
        assertEquals(2, humanOnly.size, "봇·webhook 제외")

        val humanAvg = humanOnly.sumOf { it.fragments.size }.toDouble() / humanOnly.size
        assertEquals(3.0, humanAvg, "인간 평균 = (2+4)/2 = 3, 봇 100·webhook 50 미반영")
    }
}
