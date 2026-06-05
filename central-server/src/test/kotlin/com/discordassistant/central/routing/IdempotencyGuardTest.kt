package com.discordassistant.central.routing

import com.discordassistant.central.routing.domain.service.IdempotencyGuard
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 멱등성 가드(차수 16 #243) — 윈도우 내 중복 차단/만료 후 허용. */
class IdempotencyGuardTest {
    @Test
    fun `윈도우 내 중복은 차단, 만료 후 허용`() {
        var now = 0L
        val g = IdempotencyGuard(windowMillis = 1000, nowNanos = { now })
        assertTrue(g.tryBegin(1, 2, "hi")) // 최초 허용
        assertFalse(g.tryBegin(1, 2, "hi")) // 즉시 중복 → 차단
        now += 2_000L * 1_000_000 // 2초 경과(윈도우 1초 초과)
        assertTrue(g.tryBegin(1, 2, "hi")) // 만료 → 다시 허용
    }

    @Test
    fun `다른 유저·프롬프트·길드는 독립`() {
        val g = IdempotencyGuard(windowMillis = 1000, nowNanos = { 0L })
        assertTrue(g.tryBegin(1, 2, "a"))
        assertTrue(g.tryBegin(1, 3, "a")) // 다른 유저
        assertTrue(g.tryBegin(1, 2, "b")) // 다른 프롬프트
        assertTrue(g.tryBegin(9, 2, "a")) // 다른 길드
        assertFalse(g.tryBegin(1, 2, "a")) // 동일 → 차단
    }
}
