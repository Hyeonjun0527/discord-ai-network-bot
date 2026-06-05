package com.discordassistant.central.quota.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 분당 고정 윈도우 rate limit (K-차수 15 보안). 키(예: ask:guild:user)별 요청 폭주를 막는다.
 * 카운터 저장은 [RateLimitStore] 에 위임 — 기본 인메모리, Redis 백엔드로 다중 인스턴스 분산 가능(#242).
 */
@Component
class RateLimiter(
    private val store: RateLimitStore,
    @param:Value("\${central.ratelimit.ask-per-minute:10}") private val perMinute: Int,
) {
    fun tryAcquire(key: String): Boolean = store.tryAcquire(key, perMinute, 60)
}
