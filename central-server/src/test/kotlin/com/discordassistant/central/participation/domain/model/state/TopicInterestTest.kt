package com.discordassistant.central.participation.domain.model.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P06-T013: identity 관심 tag x scene 주제 결합, GLM 자유 텍스트 미저장. */
class TopicInterestTest {
    @Test
    fun `윤리 - freeTextSelfDescription 은 항상 false (자유 텍스트 미저장)`() {
        assertFalse(TopicInterest.idle(setOf("게임")).freeTextSelfDescription)
    }

    @Test
    fun `acceptance - 라벨 매칭으로만 관심도를 계산한다`() {
        val s = TopicInterest(identityInterestTags = setOf("게임", "음악"), sceneTopicTags = setOf("게임", "요리"))
        assertEquals(setOf("게임"), s.matchedTags)
        assertEquals(0.5, s.interest) // scene tag 2개 중 1개 일치.
    }

    @Test
    fun `scene tag 가 없으면 관심도 0`() {
        assertEquals(0.0, TopicInterest.idle(setOf("게임")).interest)
    }

    @Test
    fun `관심 tag 와 겹치지 않으면 관심도 0`() {
        val s = TopicInterest(identityInterestTags = setOf("게임"), sceneTopicTags = setOf("주식"))
        assertTrue(s.matchedTags.isEmpty())
        assertEquals(0.0, s.interest)
    }
}
