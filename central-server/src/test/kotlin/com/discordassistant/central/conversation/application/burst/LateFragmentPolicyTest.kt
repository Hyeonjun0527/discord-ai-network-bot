package com.discordassistant.central.conversation.application.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * NEXA-P04-T016 acceptance: late fragment 결정이 occurredAt/receivedAt 규칙에 따라 결정론적이다.
 */
class LateFragmentPolicyTest {
    private val guild = GuildId(1L)
    private val anchor = Instant.parse("2026-01-01T12:00:00Z")
    private val policy = LateFragmentPolicy(lateArrivalWindow = Duration.ofSeconds(30))

    private fun candidate(): UtteranceBurst = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, seq = 1, at = anchor)).finalize()

    @Test
    fun `붙일 후보가 없으면 독립 late 버스트`() {
        val late = BurstTestFragments.fragment(2, seq = 2, at = anchor.minusSeconds(5))
        assertEquals(LateFragmentDecision.StandaloneLateBurst, policy.decide(null, late))
    }

    @Test
    fun `창 이내 과거 메시지는 기존 버스트 교정으로 흡수된다`() {
        // occurredAt 이 anchor 보다 10초 과거(창 30초 이내) → 흡수. receivedAt 은 한참 뒤(도착 지연)지만 결정에 무관.
        val late = BurstTestFragments.fragment(2, seq = 99, at = anchor.minusSeconds(10))
        val decision = policy.decide(candidate(), late)
        assertInstanceOf(LateFragmentDecision.CorrectExistingBurst::class.java, decision)
    }

    @Test
    fun `창을 벗어난 과거 메시지는 독립 late 버스트`() {
        val late = BurstTestFragments.fragment(2, seq = 99, at = anchor.minusSeconds(60))
        assertEquals(LateFragmentDecision.StandaloneLateBurst, policy.decide(candidate(), late))
    }

    @Test
    fun `미래(anchor 이후) 메시지는 late 대상이 아니라 독립`() {
        val notLate = BurstTestFragments.fragment(2, seq = 99, at = anchor.plusSeconds(5))
        assertEquals(LateFragmentDecision.StandaloneLateBurst, policy.decide(candidate(), notLate))
    }

    @Test
    fun `같은 입력은 도착 순서·receivedAt 과 무관하게 같은 결정을 낸다 (결정론)`() {
        val late1 = BurstTestFragments.fragment(2, seq = 99, at = anchor.minusSeconds(10))
        // 같은 occurredAt, 다른 seq(도착 순번)·다른 메시지 id 라도 occurredAt 기준이라 결정 동일.
        val late2 = BurstTestFragments.fragment(3, seq = 5, at = anchor.minusSeconds(10))
        val d1 = policy.decide(candidate(), late1)
        val d2 = policy.decide(candidate(), late2)
        assertEquals(d1::class, d2::class, "occurredAt 동일이면 결정 동일")
    }
}
