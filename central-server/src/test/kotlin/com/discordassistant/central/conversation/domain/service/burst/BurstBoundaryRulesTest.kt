package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstGapPolicy
import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.FragmentType
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * NEXA-P04-T007(다른 작성자 개입)·T008(reply 대상 변경)·T009(위치 경계)·T010(typing 연장) 경계 규칙 acceptance.
 */
class BurstBoundaryRulesTest {
    private val guild = GuildId(1L)
    private val t0 = BurstTestFragments.T0

    // ── T009: thread/채널 위치 경계 ────────────────────────────────────────────────
    @Test
    fun `다른 위치(thread 이동)면 경계다 같은 위치면 아니다`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, threadId = null))
        assertTrue(BurstBoundaryRules.crossesLocationBoundary(open, BurstTestFragments.fragment(2, threadId = 200L)))
        assertFalse(BurstBoundaryRules.crossesLocationBoundary(open, BurstTestFragments.fragment(2, threadId = null)))
    }

    // ── T008: reply 대상 변경 ─────────────────────────────────────────────────────
    @Test
    fun `reply 대상이 바뀌면 경계이고 같은 대상이면 유지된다`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, replyTo = 50L))
        assertTrue(BurstBoundaryRules.changesReplyTarget(open, BurstTestFragments.fragment(2, replyTo = 99L)))
        assertFalse(BurstBoundaryRules.changesReplyTarget(open, BurstTestFragments.fragment(2, replyTo = 50L)))
    }

    @Test
    fun `둘 다 reply 가 아니면 자유 발화라 경계가 아니다`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, replyTo = null))
        assertFalse(BurstBoundaryRules.changesReplyTarget(open, BurstTestFragments.fragment(2, replyTo = null)))
    }

    // ── T007: 다른 작성자 개입 종료 + type 분기 ──────────────────────────────────────
    @Test
    fun `다른 작성자의 일반 메시지는 OPEN 버스트를 종료시킨다`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, authorId = 1L))
        val intruder = BurstTestFragments.fragment(2, authorId = 2L, type = FragmentType.NORMAL)
        assertTrue(BurstBoundaryRules.otherAuthorEndsBurst(open, intruder))
    }

    @Test
    fun `이모지나 시스템 메시지 개입은 종료시키지 않는다 (type 규칙)`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, authorId = 1L))
        assertFalse(
            BurstBoundaryRules.otherAuthorEndsBurst(
                open,
                BurstTestFragments.fragment(2, authorId = 2L, type = FragmentType.EMOJI),
            ),
        )
        assertFalse(
            BurstBoundaryRules.otherAuthorEndsBurst(
                open,
                BurstTestFragments.fragment(3, authorId = 2L, type = FragmentType.SYSTEM),
            ),
        )
    }

    @Test
    fun `같은 작성자는 개입이 아니다`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, authorId = 1L))
        assertFalse(
            BurstBoundaryRules.otherAuthorEndsBurst(open, BurstTestFragments.fragment(2, authorId = 1L)),
        )
    }

    // ── T010: typing 연장 + hard deadline ──────────────────────────────────────────
    @Test
    fun `typing 중이면 effective gap 을 maxGap 까지 늘린다`() {
        val policy = BurstGapPolicy.DEFAULT
        val gap =
            BurstBoundaryRules.effectiveGapWithTyping(
                policy = policy,
                baselineGap = policy.defaultGap,
                typingExpiresAt = t0.plusSeconds(10),
                at = t0.plusSeconds(5),
            )
        assertEquals(policy.maxGap, gap)
    }

    @Test
    fun `typing 만료나 신호 유실 시 baseline gap 으로 돌아간다 (무한 연기 금지)`() {
        val policy = BurstGapPolicy.DEFAULT
        // typing 만료.
        assertEquals(
            policy.defaultGap,
            BurstBoundaryRules.effectiveGapWithTyping(policy, policy.defaultGap, t0.plusSeconds(10), t0.plusSeconds(11)),
        )
        // typing 신호 유실(null).
        assertEquals(
            policy.defaultGap,
            BurstBoundaryRules.effectiveGapWithTyping(policy, policy.defaultGap, null, t0.plusSeconds(5)),
        )
    }

    @Test
    fun `연장값은 maxGap hard ceiling 을 못 넘는다`() {
        val policy = BurstGapPolicy(Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(8))
        // typing 활성이어도 maxGap(8s) 으로 clamp.
        val gap =
            BurstBoundaryRules.effectiveGapWithTyping(
                policy = policy,
                baselineGap = Duration.ofHours(1),
                typingExpiresAt = t0.plusSeconds(60),
                at = t0,
            )
        assertEquals(Duration.ofSeconds(8), gap)
    }

    @Test
    fun `exceedsGap 은 경계 직전 이하면 false 직후 초과면 true`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, at = t0))
        val gap = Duration.ofSeconds(7)
        assertFalse(BurstBoundaryRules.exceedsGap(open, BurstTestFragments.fragment(2, at = t0.plusSeconds(7)), gap))
        assertTrue(BurstBoundaryRules.exceedsGap(open, BurstTestFragments.fragment(2, at = t0.plusSeconds(8)), gap))
    }
}
