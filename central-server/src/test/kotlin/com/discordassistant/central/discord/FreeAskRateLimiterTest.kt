package com.discordassistant.central.discord

import com.discordassistant.central.quota.application.FreeAskRateLimiter
import com.discordassistant.central.quota.application.InMemoryRateLimitStore
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** /무료질문 인당 rate limit(시간당·일일) — 무료 자원 남용 방지. */
class FreeAskRateLimiterTest {
    @Test
    fun `시간당 한도까지 허용하고 초과는 거부`() {
        val rl = FreeAskRateLimiter(InMemoryRateLimitStore(), perHour = 3, perDay = 100)
        repeat(3) { assertNull(rl.check(1L)) } // 3회 허용
        val denied = rl.check(1L) // 4회째 거부
        assertTrue(denied != null && denied.contains("1시간"))
    }

    @Test
    fun `일일 한도 초과는 거부`() {
        val rl = FreeAskRateLimiter(InMemoryRateLimitStore(), perHour = 1000, perDay = 3)
        repeat(3) { assertNull(rl.check(1L)) }
        val denied = rl.check(1L)
        assertTrue(denied != null && denied.contains("하루"))
    }

    @Test
    fun `사용자별 독립 카운트`() {
        val rl = FreeAskRateLimiter(InMemoryRateLimitStore(), perHour = 1, perDay = 100)
        assertNull(rl.check(1L))
        assertTrue(rl.check(1L) != null) // 1번 유저는 시간당 1회 초과
        assertNull(rl.check(2L)) // 2번 유저는 독립적으로 허용
    }

    @Test
    fun `0 이하는 무제한`() {
        val rl = FreeAskRateLimiter(InMemoryRateLimitStore(), perHour = 0, perDay = 0)
        repeat(50) { assertNull(rl.check(1L)) }
    }
}
