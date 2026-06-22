package com.discordassistant.central.socialmemory.domain.service.relationship

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P06-T006: 두 방향 분리 + 작은 분모 smoothing. */
class InteractionReciprocityTest {
    @Test
    fun `두 방향을 분리한다`() {
        val r = InteractionReciprocity(nexaInitiations = 10, memberResponses = 8, memberInitiations = 4, nexaResponses = 4)
        assertTrue(r.memberResponseRate() > 0.5) // 상대가 잘 반응.
        assertTrue(r.nexaResponseRate() > 0.5) // NEXA 도 잘 반응.
    }

    @Test
    fun `acceptance - 분모가 0 이어도 prior 로 수렴한다 (smoothing)`() {
        val empty = InteractionReciprocity(0, 0, 0, 0)
        // prior 1/2 = 0.5 로 수렴(0/0 아님).
        assertEquals(0.5, empty.memberResponseRate(), 1e-9)
        assertEquals(0.5, empty.nexaResponseRate(), 1e-9)
    }

    @Test
    fun `acceptance - 표본 1건은 극단값으로 가지 않는다 (smoothing)`() {
        val oneHit = InteractionReciprocity(nexaInitiations = 1, memberResponses = 1, memberInitiations = 0, nexaResponses = 0)
        // (1+1)/(1+2) = 0.667 < 1.0 — 1/1 이라도 1.0 으로 과신하지 않는다.
        assertTrue(oneHit.memberResponseRate() < 1.0)
        assertTrue(oneHit.memberResponseRate() > 0.5)
    }

    @Test
    fun `responses 가 opportunities 를 넘으면 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            InteractionReciprocity(nexaInitiations = 1, memberResponses = 2, memberInitiations = 0, nexaResponses = 0)
        }
    }
}
