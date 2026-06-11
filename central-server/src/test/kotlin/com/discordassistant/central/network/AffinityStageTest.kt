package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.domain.model.AffinityStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AffinityStageTest {
    @Test
    fun `점수 경계는 단계로 단조 변환된다`() {
        assertEquals(AffinityStage.STRANGER, AffinityStage.forScore(-1))
        assertEquals(AffinityStage.STRANGER, AffinityStage.forScore(9))
        assertEquals(AffinityStage.GETTING_TO_KNOW, AffinityStage.forScore(10))
        assertEquals(AffinityStage.FRIENDLY, AffinityStage.forScore(50))
        assertEquals(AffinityStage.BEST_FRIEND, AffinityStage.forScore(150))
    }

    @Test
    fun `다음 단계까지 남은 점수는 음수가 되지 않는다`() {
        assertEquals(10L, AffinityStage.scoreToNext(0))
        assertEquals(1L, AffinityStage.scoreToNext(9))
        assertEquals(40L, AffinityStage.scoreToNext(10))
        assertEquals(0L, AffinityStage.scoreToNext(150))
        assertNull(AffinityStage.next(AffinityStage.BEST_FRIEND))
    }

    @Test
    fun `단계 임계값은 오름차순이다`() {
        val thresholds = AffinityStage.entries.map { it.threshold }
        assertEquals(thresholds.sorted(), thresholds)
        assertTrue(thresholds.distinct().size == thresholds.size)
    }
}
