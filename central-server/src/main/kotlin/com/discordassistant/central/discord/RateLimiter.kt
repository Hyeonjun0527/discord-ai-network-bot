package com.discordassistant.central.discord

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 분당 고정 윈도우 rate limit (K-차수 15 보안). 키(예: ask:guild:user)별 요청 폭주를 막는다.
 */
@Component
class RateLimiter(
    @param:Value("\${central.ratelimit.ask-per-minute:10}") private val perMinute: Int,
) {
    private data class Window(var count: Int, var startNanos: Long)

    private val windows = ConcurrentHashMap<String, Window>()
    private val windowNanos = TimeUnit.MINUTES.toNanos(1)

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = System.nanoTime()
        val w = windows.getOrPut(key) { Window(0, now) }
        if (now - w.startNanos >= windowNanos) {
            w.count = 0
            w.startNanos = now
        }
        if (w.count >= perMinute) return false
        w.count++
        return true
    }
}
