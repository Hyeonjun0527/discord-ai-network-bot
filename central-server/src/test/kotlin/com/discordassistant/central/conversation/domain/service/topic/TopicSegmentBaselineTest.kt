package com.discordassistant.central.conversation.domain.service.topic

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** NEXA-P05-T013: 임베딩·GLM 없이 reply graph, 시간 gap, keyword 변화로 topic segment 후보를 만든다. */
class TopicSegmentBaselineTest {
    private val t0 = Instant.parse("2026-01-01T10:00:00Z")

    private fun input(
        id: Long,
        startMin: Long,
        replyTo: BurstId? = null,
        keywords: Set<String> = emptySet(),
    ) = TopicBurstInput(
        burstId = BurstId("burst:$id"),
        startedAt = t0.plusSeconds(startMin * 60),
        lastFragmentAt = t0.plusSeconds(startMin * 60 + 5),
        replyToBurst = replyTo,
        keywords = keywords,
    )

    @Test
    fun `빈 입력이면 빈 segment`() {
        assertTrue(TopicSegmentBaseline.segment(emptyList()).isEmpty())
    }

    @Test
    fun `시간 gap 이 크면 새 주제 구간으로 나뉜다`() {
        val inputs =
            listOf(
                input(1, startMin = 0),
                input(2, startMin = 1),
                input(3, startMin = 30), // 30분 gap > 10분 maxGap → 경계.
            )
        val segments = TopicSegmentBaseline.segment(inputs)
        assertEquals(2, segments.size)
        assertEquals(listOf(BurstId("burst:1"), BurstId("burst:2")), segments[0].burstIds)
        assertEquals(listOf(BurstId("burst:3")), segments[1].burstIds)
    }

    @Test
    fun `reply 로 직전 segment 에 이어지면 같은 주제 (keyword 달라도 유지)`() {
        val inputs =
            listOf(
                input(1, startMin = 0, keywords = setOf("apple")),
                input(2, startMin = 1, replyTo = BurstId("burst:1"), keywords = setOf("banana")),
            )
        val segments = TopicSegmentBaseline.segment(inputs)
        assertEquals(1, segments.size)
    }

    @Test
    fun `keyword 가 완전히 바뀌면 새 주제로 나뉜다`() {
        val inputs =
            listOf(
                input(1, startMin = 0, keywords = setOf("apple", "fruit")),
                input(2, startMin = 1, keywords = setOf("car", "engine")), // 겹침 0 < 0.2 → 경계.
            )
        val segments = TopicSegmentBaseline.segment(inputs)
        assertEquals(2, segments.size)
    }

    @Test
    fun `keyword 가 충분히 겹치면 같은 주제로 유지된다`() {
        val inputs =
            listOf(
                input(1, startMin = 0, keywords = setOf("apple", "fruit", "sweet")),
                input(2, startMin = 1, keywords = setOf("apple", "fruit")), // 자카드 2/3 > 0.2 → 같은 주제.
            )
        val segments = TopicSegmentBaseline.segment(inputs)
        assertEquals(1, segments.size)
    }

    @Test
    fun `옵트아웃 빈 keyword 는 keyword 경계를 만들지 않는다 (시간 신호만)`() {
        val inputs =
            listOf(
                input(1, startMin = 0, keywords = emptySet()),
                input(2, startMin = 1, keywords = emptySet()),
            )
        // gap 작고 keyword 없음 → keyword 로 경계 안 생김 → 한 segment.
        assertEquals(1, TopicSegmentBaseline.segment(inputs).size)
    }

    @Test
    fun `custom config 의 maxGap 을 따른다`() {
        val inputs = listOf(input(1, startMin = 0), input(2, startMin = 2))
        val tight = TopicSegmentConfig(maxGap = Duration.ofMinutes(1))
        // 2분 gap > 1분 → 경계.
        assertEquals(2, TopicSegmentBaseline.segment(inputs, tight).size)
    }
}
