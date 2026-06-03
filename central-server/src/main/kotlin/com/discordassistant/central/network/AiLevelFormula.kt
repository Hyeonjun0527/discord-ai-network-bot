package com.discordassistant.central.network

/**
 * 서버(길드) AI 활동 레벨/경험치 공식(Phase 1). 순수 함수 모음 — 의존성 0, 전부 정수(Long/Int) 연산.
 *
 * 누적 임계: `xpForLevel(n) = 50 * (n-1) * n` → L1=0, L2=100, L3=300, L4=600, L5=1000…
 * 부동소수/오버플로를 피하려고 정수 루프로 레벨을 구한다.
 */
object AiLevelFormula {
    /** 질문(/ask) 답변 성공 1건당 획득 경험치. 운영 중 조정 가능하도록 상수화. */
    const val XP_PER_ASK_SUCCESS = 10L

    /** 레벨 [level] 에 도달하기 위한 누적 경험치 임계값(level<=1 이면 0). */
    fun xpForLevel(level: Int): Long {
        if (level <= 1) return 0L
        val n = level.toLong()
        return 50L * (n - 1L) * n
    }

    /** 누적 경험치 [totalXp] 에 해당하는 활동 레벨(최소 1). 음수 입력은 1로 처리. */
    fun levelForXp(totalXp: Long): Int {
        if (totalXp <= 0L) return 1
        var level = 1
        // xpForLevel 은 단조 증가하므로, 다음 레벨 임계를 넘는 동안 올린다.
        while (xpForLevel(level + 1) <= totalXp) {
            level++
        }
        return level
    }

    /** 현재 경험치 [totalXp] 기준 다음 레벨까지 남은 경험치(>=0). */
    fun xpToNextLevel(totalXp: Long): Long {
        val safe = if (totalXp < 0L) 0L else totalXp
        val level = levelForXp(safe)
        val nextThreshold = xpForLevel(level + 1)
        val remaining = nextThreshold - safe
        return if (remaining < 0L) 0L else remaining
    }

    /**
     * 현재 레벨 구간 내에서 (획득한 경험치, 이번 레벨에 필요한 경험치) 쌍.
     * 예: L2 진입(=100xp) 직후엔 (0, 200) — L3 까지 200 필요.
     */
    fun progressInLevel(totalXp: Long): Pair<Long, Long> {
        val safe = if (totalXp < 0L) 0L else totalXp
        val level = levelForXp(safe)
        val floor = xpForLevel(level)
        val ceil = xpForLevel(level + 1)
        val gained = safe - floor
        val needed = ceil - floor
        return gained to needed
    }
}
