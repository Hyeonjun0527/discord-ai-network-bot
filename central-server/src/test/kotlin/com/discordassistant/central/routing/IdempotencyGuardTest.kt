package com.discordassistant.central.routing

import com.discordassistant.central.routing.domain.service.IdempotencyDecision
import com.discordassistant.central.routing.domain.service.IdempotencyGuard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 멱등/한도 가드(#243, 개선) — 동일요청 최대 N회 허용, 채널 하루 상한. */
class IdempotencyGuardTest {
    @Test
    fun `같은 요청은 윈도우 안에서 최대 5번 허용하고 6번째부터 막는다`() {
        var now = 0L
        val g =
            IdempotencyGuard(
                windowMillis = 1000,
                maxDuplicatesPerWindow = 5,
                channelDailyLimit = 1000, // 채널 상한이 개입하지 않게 크게
                nowNanos = { now },
            )
        repeat(5) { assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 10, 2, "hi")) } // 5번까지 허용
        assertEquals(IdempotencyDecision.DUPLICATE, g.begin(1, 10, 2, "hi")) // 6번째 차단
        now += 2_000L * 1_000_000 // 윈도우(1초) 만료
        assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 10, 2, "hi")) // 리셋 → 다시 허용
    }

    @Test
    fun `다른 유저·프롬프트·길드는 동일요청 카운트가 독립`() {
        val g = IdempotencyGuard(windowMillis = 1000, maxDuplicatesPerWindow = 1, channelDailyLimit = 1000, nowNanos = { 0L })
        assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 10, 2, "a"))
        assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 10, 3, "a")) // 다른 유저
        assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 10, 2, "b")) // 다른 프롬프트
        assertEquals(IdempotencyDecision.ALLOW, g.begin(9, 10, 2, "a")) // 다른 길드
        assertEquals(IdempotencyDecision.DUPLICATE, g.begin(1, 10, 2, "a")) // 동일(2번째, 상한 1) → 차단
    }

    @Test
    fun `채널은 하루 상한을 넘으면 막고 다른 채널은 독립`() {
        val g =
            IdempotencyGuard(
                windowMillis = 1000,
                maxDuplicatesPerWindow = 100, // 동일요청 상한이 개입하지 않게
                channelDailyLimit = 3,
                nowNanos = { 0L },
            )
        assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 10, 2, "a"))
        assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 10, 2, "b"))
        assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 10, 2, "c")) // 채널 3회
        assertEquals(IdempotencyDecision.CHANNEL_DAILY_LIMIT, g.begin(1, 10, 2, "d")) // 4번째 → 상한 초과
        assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 99, 2, "d")) // 다른 채널은 독립
    }

    @Test
    fun `중복으로 막힌 요청은 채널 하루 카운트를 소모하지 않는다`() {
        val g =
            IdempotencyGuard(
                windowMillis = 1000,
                maxDuplicatesPerWindow = 1,
                channelDailyLimit = 2,
                nowNanos = { 0L },
            )
        assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 10, 2, "a")) // 채널 1
        assertEquals(IdempotencyDecision.DUPLICATE, g.begin(1, 10, 2, "a")) // dup 차단 → 채널 안 셈
        assertEquals(IdempotencyDecision.ALLOW, g.begin(1, 10, 2, "b")) // 채널 2
        assertEquals(IdempotencyDecision.CHANNEL_DAILY_LIMIT, g.begin(1, 10, 2, "c")) // 채널 3 → 초과
    }
}
