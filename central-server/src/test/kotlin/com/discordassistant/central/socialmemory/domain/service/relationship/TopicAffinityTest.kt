package com.discordassistant.central.socialmemory.domain.service.relationship

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** NEXA-P06-T009: 명시 tag + 빈도, 민감 주제 자동 추론·원문 장기 저장 금지. */
class TopicAffinityTest {
    private val tag = ExplicitTopicTag("게임")
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    @Test
    fun `acceptance - rawContentStored 는 항상 false (원문 장기 저장 금지)`() {
        assertFalse(TopicAffinity.empty(tag).rawContentStored)
    }

    @Test
    fun `빈 tag label 은 거부된다 (명시 tag 만)`() {
        assertThrows(IllegalArgumentException::class.java) { ExplicitTopicTag("  ") }
    }

    @Test
    fun `참여가 없으면 affinity 0`() {
        assertEquals(0.0, TopicAffinity.empty(tag).affinity(now = t0))
    }

    @Test
    fun `참여 빈도가 많을수록 affinity 가 높다`() {
        var few = TopicAffinity.empty(tag)
        repeat(2) { few = few.engage(t0) }
        var many = TopicAffinity.empty(tag)
        repeat(20) { many = many.engage(t0) }
        assertTrue(many.affinity(now = t0) > few.affinity(now = t0))
    }

    @Test
    fun `시간 감쇠 - 오래되면 affinity 가 약화된다`() {
        var s = TopicAffinity.empty(tag)
        repeat(15) { s = s.engage(t0) }
        assertTrue(s.affinity(now = t0.plus(Duration.ofDays(60))) < s.affinity(now = t0))
    }
}
