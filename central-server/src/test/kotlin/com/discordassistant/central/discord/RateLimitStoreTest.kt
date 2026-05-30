package com.discordassistant.central.discord

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 분산 rate limit 저장소(차수 16 #242) — 인메모리 윈도우 동작. */
class RateLimitStoreTest {
    @Test
    fun `한도까지 허용하고 초과는 차단`() {
        val store = InMemoryRateLimitStore()
        repeat(3) { assertTrue(store.tryAcquire("k", limit = 3, windowSeconds = 60)) }
        assertFalse(store.tryAcquire("k", limit = 3, windowSeconds = 60)) // 4번째 차단
    }

    @Test
    fun `키별 독립`() {
        val store = InMemoryRateLimitStore()
        assertTrue(store.tryAcquire("a", 1, 60))
        assertFalse(store.tryAcquire("a", 1, 60))
        assertTrue(store.tryAcquire("b", 1, 60)) // 다른 키는 독립
    }
}
