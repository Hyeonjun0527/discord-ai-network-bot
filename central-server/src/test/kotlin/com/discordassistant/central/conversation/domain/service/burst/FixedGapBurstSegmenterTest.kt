package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstGapPolicy
import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.burst.BurstSession
import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.FragmentType
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * NEXA-P04-T004 acceptance: 경계 직전/직후 fixture 가 예상대로 분리된다.
 * 더해 nickname-burst.v1 fixture 순서를 재생해 T007(다른 작성자 개입)·T009(위치) 통합 분할도 검증한다.
 */
class FixedGapBurstSegmenterTest {
    private val guild = GuildId(1L)
    private val t0 = BurstTestFragments.T0

    private val seg = FixedGapBurstSegmenter()
    private val defaultPolicy = BurstGapPolicy.DEFAULT

    private fun emptySession(channelId: Long = 100L): BurstSession = BurstSession.empty(BurstLocationKey(ChannelId(channelId), null))

    @Test
    fun `진행 중 버스트가 없으면 새 버스트를 시작한다`() {
        val decision = seg.decide(guild, emptySession(), BurstTestFragments.fragment(1), defaultPolicy)
        assertTrue(decision is SegmentDecision.StartNew)
    }

    @Test
    fun `gap 경계 직전 조각은 같은 버스트에 이어붙는다`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, seq = 1, at = t0))
        val session = emptySession().withOpenBurst(open)
        // 정확히 7초(defaultGap 이하) → Append.
        val decision =
            seg.decide(guild, session, BurstTestFragments.fragment(2, seq = 2, at = t0.plusSeconds(7)), defaultPolicy)
        assertTrue(decision is SegmentDecision.Append)
    }

    @Test
    fun `gap 경계 직후 조각은 기존 버스트를 finalize 하고 새로 시작한다`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, seq = 1, at = t0))
        val session = emptySession().withOpenBurst(open)
        // 8초(defaultGap 초과) → FinalizeThenStart.
        val decision =
            seg.decide(guild, session, BurstTestFragments.fragment(2, seq = 2, at = t0.plusSeconds(8)), defaultPolicy)
        assertTrue(decision is SegmentDecision.FinalizeThenStart)
        decision as SegmentDecision.FinalizeThenStart
        assertEquals(listOf(MessageId(1)), decision.finalized.messageIds)
        assertEquals(listOf(MessageId(2)), decision.started.messageIds)
    }

    @Test
    fun `reply target 변경은 시간과 무관하게 즉시 경계다 (T008)`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, seq = 1, at = t0, replyTo = 50L))
        val session = emptySession().withOpenBurst(open)
        // 1초 뒤(gap 이하)지만 reply 대상이 다르므로 경계.
        val decision =
            seg.decide(
                guild,
                session,
                BurstTestFragments.fragment(2, seq = 2, at = t0.plusSeconds(1), replyTo = 99L),
                defaultPolicy,
            )
        assertTrue(decision is SegmentDecision.FinalizeThenStart)
    }

    @Test
    fun `typing 중이면 baseline gap 을 넘는 간격도 같은 버스트로 유지된다 (T010)`() {
        // defaultGap 7s, maxGap 30s
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, seq = 1, at = t0))
        val session = emptySession().withOpenBurst(open)
        // 10초 간격(baseline 7s 초과)이지만 typing 활성 → maxGap(30s) 내라 Append.
        val decision =
            seg.decide(
                guild,
                session,
                BurstTestFragments.fragment(2, seq = 2, at = t0.plusSeconds(10)),
                defaultPolicy,
                typingExpiresAt = t0.plusSeconds(15),
            )
        assertTrue(decision is SegmentDecision.Append)
    }

    @Test
    fun `다른 작성자의 일반 메시지 개입은 기존 작성자 OPEN 버스트를 종료시킨다 (T007)`() {
        val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(1, authorId = 1L))
        val session = emptySession().withOpenBurst(open)
        val finalized = seg.finalizeOnIntrusion(session, BurstTestFragments.fragment(2, authorId = 2L))
        assertEquals(1, finalized.size)
        assertEquals(AuthorId(1L), finalized.first().authorId)

        // 이모지 개입은 종료시키지 않는다.
        assertTrue(
            seg
                .finalizeOnIntrusion(
                    session,
                    BurstTestFragments.fragment(3, authorId = 2L, type = FragmentType.EMOJI),
                ).isEmpty(),
        )
    }

    /**
     * nickname-burst.v1 fixture 순서를 그대로 재생해 expected.bursts 와 일치하는지 검증한다(T004/T007 통합).
     * fixture: A×4(연속) → B×2(연속) → C×1 → D×1(개입) → C×3(재개) = 5 버스트.
     */
    @Test
    fun `nickname-burst fixture 시퀀스가 expected 버스트로 분할된다`() {
        // gap 을 넉넉히(60s) 둬 모든 분할이 작성자 개입(T007)으로만 결정되게 한다(fixture 는 1초 간격 연속).
        val policy = BurstGapPolicy(Duration.ofSeconds(60), Duration.ofSeconds(2), Duration.ofSeconds(120))

        // (authorId, messageId) 순서 — fixture events seq 1..11.
        val script =
            listOf(
                1L to 1L,
                1L to 2L,
                1L to 3L,
                1L to 4L, // A×4
                2L to 5L,
                2L to 6L, // B×2
                3L to 7L, // C
                4L to 8L, // D 개입
                3L to 9L,
                3L to 10L,
                3L to 11L, // C 재개
            )

        var session = emptySession()
        val finalizedBursts = mutableListOf<UtteranceBurst>()

        script.forEachIndexed { idx, (author, message) ->
            val frag =
                BurstTestFragments.fragment(
                    messageId = message,
                    authorId = author,
                    seq = (idx + 1).toLong(),
                    at = t0.plusSeconds(idx.toLong()),
                )
            // T007: 다른 작성자 개입으로 종료되는 기존 버스트를 먼저 닫는다.
            seg.finalizeOnIntrusion(session, frag).forEach { closed ->
                finalizedBursts += closed
                session = session.withoutAuthor(closed.authorId)
            }
            session = applyDecision(session, seg.decide(guild, session, frag, policy), finalizedBursts)
        }
        // 남은 OPEN 버스트도 종료(스트림 끝).
        session.openBursts.values
            .sortedBy { it.startedAt }
            .forEach { finalizedBursts += it.finalize() }

        val resultMessageIds = finalizedBursts.map { it.messageIds.map { id -> id.value } }
        assertEquals(
            listOf(
                listOf(1L, 2L, 3L, 4L), // burst-a-001
                listOf(7L), // burst-c-001 (D 개입으로 종료)
                listOf(5L, 6L), // burst-b-001
                listOf(8L), // burst-d-001
                listOf(9L, 10L, 11L), // burst-c-002 (재개)
            ).sortedBy { it.first() },
            resultMessageIds.sortedBy { it.first() },
        )
    }

    private fun applyDecision(
        session: BurstSession,
        decision: SegmentDecision,
        finalizedBursts: MutableList<UtteranceBurst>,
    ): BurstSession =
        when (decision) {
            is SegmentDecision.StartNew -> session.withOpenBurst(decision.started)
            is SegmentDecision.Append -> session.withOpenBurst(decision.updated)
            is SegmentDecision.FinalizeThenStart -> {
                finalizedBursts += decision.finalized
                session.withoutAuthor(decision.finalized.authorId).withOpenBurst(decision.started)
            }
        }
}
