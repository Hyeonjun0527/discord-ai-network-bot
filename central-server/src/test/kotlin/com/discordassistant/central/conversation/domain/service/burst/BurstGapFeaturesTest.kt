package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * NEXA-P04-T006 acceptance: feature 값이 미래 이벤트를 참조하지 않고 replay 에서 동일하다.
 */
class BurstGapFeaturesTest {
    private val guild = GuildId(1L)
    private val t0 = Instant.parse("2026-01-01T11:15:01Z")

    @Test
    fun `gapSincePrevious 는 직전 버스트 마지막 조각과의 간격이고 버스트 없으면 null`() {
        assertNull(BurstGapFeatures.gapSincePrevious(null, BurstTestFragments.fragment(1, at = t0)))
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, at = t0))
        assertEquals(
            Duration.ofSeconds(3),
            BurstGapFeatures.gapSincePrevious(open, BurstTestFragments.fragment(2, at = t0.plusSeconds(3))),
        )
    }

    @Test
    fun `authorRecentCadence 는 OPEN 버스트 내부 평균 간격이고 조각 1개면 null`() {
        val single = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, seq = 1, at = t0))
        assertNull(BurstGapFeatures.authorRecentCadence(single))

        // 1초·3초 간격 → 평균 2초(정수 밀리초, replay 결정론).
        val multi =
            single
                .append(BurstTestFragments.fragment(2, seq = 2, at = t0.plusSeconds(1)))
                .append(BurstTestFragments.fragment(3, seq = 3, at = t0.plusSeconds(4)))
        assertEquals(Duration.ofSeconds(2), BurstGapFeatures.authorRecentCadence(multi))
    }

    @Test
    fun `isTypingActive 는 만료 전이면 true 만료 후나 신호 없으면 false (미래 비참조)`() {
        val expires = t0.plusSeconds(10)
        assertTrue(BurstGapFeatures.isTypingActive(expires, t0.plusSeconds(5)))
        assertFalse(BurstGapFeatures.isTypingActive(expires, t0.plusSeconds(10)))
        assertFalse(BurstGapFeatures.isTypingActive(null, t0))
    }

    @Test
    fun `같은 입력 replay 는 동일 feature 를 만든다`() {
        val open =
            UtteranceBurst
                .open(guild, BurstTestFragments.fragment(1, seq = 1, at = t0))
                .append(BurstTestFragments.fragment(2, seq = 2, at = t0.plusSeconds(2)))
        val next = BurstTestFragments.fragment(3, seq = 3, at = t0.plusSeconds(5))
        assertEquals(
            BurstGapFeatures.gapSincePrevious(open, next),
            BurstGapFeatures.gapSincePrevious(open, next),
        )
        assertEquals(
            BurstGapFeatures.authorRecentCadence(open),
            BurstGapFeatures.authorRecentCadence(open),
        )
    }
}
