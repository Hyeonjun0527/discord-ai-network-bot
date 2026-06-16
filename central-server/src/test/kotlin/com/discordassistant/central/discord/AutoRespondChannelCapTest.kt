package com.discordassistant.central.discord

import com.discordassistant.central.quota.application.InMemoryRateLimitStore
import com.discordassistant.central.quota.application.RateLimiter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 자동응답 채널 단위 비용 캡(분당 상한). DiscordBot.handleAutoRespond 가 쓰는 키
 * `autorespond:{guild}:{channel}` 의 가드 동작을 순수하게 검증한다 — 채널 단위로 한도가 적용되고,
 * 한도를 넘으면 거부(조용히 드롭)되며, 다른 채널/길드는 독립이다. 키에 user/admin 성분이 없으므로
 * **관리자도 이 채널 캡을 우회하지 못한다**(per-user ask 쿨다운의 isAdmin 우회와 별개).
 */
class AutoRespondChannelCapTest {
    private fun limiter(perMinute: Int) = RateLimiter(InMemoryRateLimitStore(), perMinute = perMinute)

    private fun key(
        guildId: Long,
        channelId: Long,
    ) = "autorespond:$guildId:$channelId"

    @Test
    fun `채널 분당 한도까지 허용하고 초과는 거부한다`() {
        val rl = limiter(perMinute = 3)
        repeat(3) { assertTrue(rl.tryAcquire(key(1L, 10L))) } // 3회 허용
        assertFalse(rl.tryAcquire(key(1L, 10L))) // 4회째 거부(조용히 드롭)
    }

    @Test
    fun `채널마다 독립으로 카운트한다`() {
        val rl = limiter(perMinute = 1)
        assertTrue(rl.tryAcquire(key(1L, 10L)))
        assertFalse(rl.tryAcquire(key(1L, 10L))) // 같은 채널 초과
        assertTrue(rl.tryAcquire(key(1L, 11L))) // 같은 길드 다른 채널은 독립
        assertTrue(rl.tryAcquire(key(2L, 10L))) // 다른 길드 같은 채널 번호도 독립
    }

    @Test
    fun `여러 사용자가 떠들어도 채널 단위로 합산되어 캡이 걸린다`() {
        // N명이 한 채널에서 떠들면 키에 userId 가 없으므로 채널 단위로 합산 → N배 비용 폭주를 막는다.
        val rl = limiter(perMinute = 2)
        assertTrue(rl.tryAcquire(key(1L, 10L))) // user A
        assertTrue(rl.tryAcquire(key(1L, 10L))) // user B
        assertFalse(rl.tryAcquire(key(1L, 10L))) // user C 는 채널 캡에 막힘(관리자라도 동일 키라 우회 불가)
    }
}
