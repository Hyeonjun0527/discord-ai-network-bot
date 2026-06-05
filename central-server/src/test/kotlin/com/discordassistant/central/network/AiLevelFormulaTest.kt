package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.domain.model.AiLevelFormula
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 활동 레벨 공식의 경계·단조성·역함수 일관성(순수 정수 연산). */
class AiLevelFormulaTest {
    @Test
    fun `누적 임계는 50·(n-1)·n 이다`() {
        assertEquals(0L, AiLevelFormula.xpForLevel(1))
        assertEquals(100L, AiLevelFormula.xpForLevel(2))
        assertEquals(300L, AiLevelFormula.xpForLevel(3))
        assertEquals(600L, AiLevelFormula.xpForLevel(4))
        assertEquals(1000L, AiLevelFormula.xpForLevel(5))
        // level<=1 은 0
        assertEquals(0L, AiLevelFormula.xpForLevel(0))
        assertEquals(0L, AiLevelFormula.xpForLevel(-3))
    }

    @Test
    fun `levelForXp 경계 — 임계 직전·직후`() {
        assertEquals(1, AiLevelFormula.levelForXp(0))
        assertEquals(1, AiLevelFormula.levelForXp(99)) // L2 직전
        assertEquals(2, AiLevelFormula.levelForXp(100)) // L2 정확히
        assertEquals(2, AiLevelFormula.levelForXp(299)) // L3 직전
        assertEquals(3, AiLevelFormula.levelForXp(300)) // L3 정확히
        assertEquals(5, AiLevelFormula.levelForXp(1000))
    }

    @Test
    fun `음수 입력은 레벨 1`() {
        assertEquals(1, AiLevelFormula.levelForXp(-1))
        assertEquals(1, AiLevelFormula.levelForXp(Long.MIN_VALUE))
    }

    @Test
    fun `levelForXp 는 단조 증가한다`() {
        var prev = 1
        var xp = 0L
        while (xp <= 5_000L) {
            val lvl = AiLevelFormula.levelForXp(xp)
            assertTrue(lvl >= prev, "레벨이 줄어듦: xp=$xp lvl=$lvl prev=$prev")
            prev = lvl
            xp += 7L
        }
    }

    @Test
    fun `역함수 일관 — levelForXp(xpForLevel(n)) == n`() {
        for (n in 1..50) {
            assertEquals(n, AiLevelFormula.levelForXp(AiLevelFormula.xpForLevel(n)), "n=$n 역함수 불일치")
        }
    }

    @Test
    fun `xpToNextLevel 은 다음 임계까지 남은 양`() {
        assertEquals(100L, AiLevelFormula.xpToNextLevel(0)) // L1->L2 까지 100
        assertEquals(1L, AiLevelFormula.xpToNextLevel(99))
        assertEquals(200L, AiLevelFormula.xpToNextLevel(100)) // L2->L3 까지 200
    }

    @Test
    fun `xpToNextLevel 음수 입력은 L1 기준`() {
        assertEquals(100L, AiLevelFormula.xpToNextLevel(-100))
    }

    @Test
    fun `progressInLevel 은 현재 구간 획득·필요 경험치`() {
        // L2 진입 직후(100xp): 구간 시작=100, 다음=300 → (0, 200)
        assertEquals(0L to 200L, AiLevelFormula.progressInLevel(100))
        // 150xp: (50, 200)
        assertEquals(50L to 200L, AiLevelFormula.progressInLevel(150))
        // 0xp: L1 구간 (0, 100)
        assertEquals(0L to 100L, AiLevelFormula.progressInLevel(0))
    }

    @Test
    fun `XP 상수는 10`() {
        assertEquals(10L, AiLevelFormula.XP_PER_ASK_SUCCESS)
    }
}
