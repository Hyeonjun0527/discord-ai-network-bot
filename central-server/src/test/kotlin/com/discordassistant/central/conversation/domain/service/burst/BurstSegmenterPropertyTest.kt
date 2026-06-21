package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstGapPolicy
import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.burst.BurstSession
import com.discordassistant.central.conversation.domain.model.burst.MessageFragment
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * NEXA-P04-T021 — 버스트 segmenter property-based 테스트(수동 생성기; 레포에 kotest/jqwik 미도입이라 seeded
 * [Random] 으로 결정론적 무작위 케이스를 생성한다).
 *
 * **acceptance(T021) — 같은 multiset·정렬 규칙이면 항상 같은 projection**: 같은 fragment 집합을 (1) 임의 순서로
 * 교란하고 (2) 임의로 중복시켜도, 어댑터 계약(중복 제거 + [MessageFragment.chronology] 정렬)을 적용한 뒤 segmenter
 * 에 흘려보내면 종료 버스트 projection 이 정렬 기준 케이스와 항상 동일하다. 각 fragment 는 정확히 한 버스트에만
 * 속한다(분할/병합 불변식). seed 별 입력을 100회 돌려 회귀를 잡는다.
 */
class BurstSegmenterPropertyTest {
    private val guild = GuildId(1L)
    private val seg = FixedGapBurstSegmenter()
    private val policy = BurstGapPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(60))

    @Test
    fun `shuffled and duplicated multisets yield the same burst projection (T021)`() {
        repeat(CASES) { seed ->
            val rng = Random(seed.toLong())
            val canonical = generateFragments(rng)

            val baseline = project(canonical)

            // (1) 순서 교란 + (2) 임의 중복 → 어댑터 계약(중복 제거 + chronology 정렬) 적용 → 같은 정규 입력.
            val perturbed = duplicateRandomly(canonical.shuffled(rng), rng)
            val normalized = perturbed.distinctBy { it.messageId.value }.sortedWith(MessageFragment.chronology)
            val replayed = project(normalized)

            assertEquals(baseline, replayed, "seed=$seed: 교란·중복 후에도 같은 projection 이어야 한다")
        }
    }

    @Test
    fun `every fragment belongs to exactly one finalized burst (T021 partition invariant)`() {
        repeat(CASES) { seed ->
            val rng = Random((seed + CASES).toLong())
            val canonical = generateFragments(rng)
            val projection = project(canonical)

            val assigned = projection.flatten()
            assertEquals(
                canonical.map { it.messageId.value }.sorted(),
                assigned.sorted(),
                "seed=$seed: 모든 fragment 가 정확히 한 버스트에 속해야 한다(누락·중복 금지)",
            )
        }
    }

    /** 무작위 작성자·gap·위치(같은 채널)로 결정론적 fragment 스트림을 만든다(seq/시각 단조 증가). */
    private fun generateFragments(rng: Random): List<MessageFragment> {
        val count = 5 + rng.nextInt(20)
        var at = BASE_INSTANT
        return (1..count).map { i ->
            at = at.plusSeconds((1 + rng.nextInt(20)).toLong()) // 임의 gap(일부는 경계 초과).
            MessageFragment(
                messageId = MessageId(i.toLong()),
                authorId = AuthorId((1 + rng.nextInt(3)).toLong()), // 작성자 3명 — 개입 다양화.
                channelId = ChannelId(100L),
                sourceSequence = i.toLong(),
                occurredAt = at,
                content = MessageContent.Available("text-$i"),
                replyTo = null,
                threadId = null,
                type = com.discordassistant.central.conversation.domain.model.burst.FragmentType.NORMAL,
            )
        }
    }

    /** 일부 fragment 를 그대로 한 번 더 끼워넣어 중복 수신(at-least-once)을 모사한다. */
    private fun duplicateRandomly(
        fragments: List<MessageFragment>,
        rng: Random,
    ): List<MessageFragment> =
        buildList {
            fragments.forEach { f ->
                add(f)
                if (rng.nextBoolean()) add(f) // 중복본(정확히 동일 — distinctBy 가 제거).
            }
        }

    /** 정렬된 fragment 스트림을 segmenter 에 흘려 종료 버스트의 messageId 리스트(시작순 정렬)를 만든다. */
    private fun project(fragments: List<MessageFragment>): List<List<Long>> {
        var session = BurstSession.empty(BurstLocationKey(ChannelId(100L), null))
        val finalized = mutableListOf<UtteranceBurst>()

        fragments.forEach { frag ->
            seg.finalizeOnIntrusion(session, frag).forEach { closed ->
                finalized += closed
                session = session.withoutAuthor(closed.authorId)
            }
            session =
                when (val d = seg.decide(guild, session, frag, policy)) {
                    is SegmentDecision.StartNew -> session.withOpenBurst(d.started)
                    is SegmentDecision.Append -> session.withOpenBurst(d.updated)
                    is SegmentDecision.FinalizeThenStart -> {
                        finalized += d.finalized
                        session.withoutAuthor(d.finalized.authorId).withOpenBurst(d.started)
                    }
                }
        }
        session.openBursts.values.forEach { finalized += it.finalize() }

        return finalized
            .map { burst -> burst.messageIds.map { it.value } }
            .sortedWith(compareBy({ it.first() }, { it.size }))
    }

    companion object {
        private const val CASES = 100
        private val BASE_INSTANT: Instant = Instant.parse("2026-01-01T11:15:01Z")
    }
}
