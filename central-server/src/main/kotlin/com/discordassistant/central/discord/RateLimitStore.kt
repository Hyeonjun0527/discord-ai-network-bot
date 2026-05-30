package com.discordassistant.central.discord

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Rate limit 카운터 저장소(차수 16 #242). 다중 인스턴스 분산을 위해 백엔드를 교체 가능하게 추상화.
 * 기본은 인메모리(단일 인스턴스); Redis 백엔드를 켜면 여러 인스턴스가 카운터를 공유한다.
 */
interface RateLimitStore {
    /** key 의 현재 윈도우 카운트를 1 올리고, limit 이하이면 true. windowSeconds 후 리셋. */
    fun tryAcquire(
        key: String,
        limit: Int,
        windowSeconds: Long,
    ): Boolean
}

/** 인메모리 고정 윈도우(단일 인스턴스 기본). */
@Component
class InMemoryRateLimitStore : RateLimitStore {
    private data class Window(
        var count: Int,
        var startNanos: Long,
    )

    private val windows = ConcurrentHashMap<String, Window>()

    @Synchronized
    override fun tryAcquire(
        key: String,
        limit: Int,
        windowSeconds: Long,
    ): Boolean {
        val now = System.nanoTime()
        val windowNanos = TimeUnit.SECONDS.toNanos(windowSeconds)
        val w = windows.getOrPut(key) { Window(0, now) }
        if (now - w.startNanos >= windowNanos) {
            w.count = 0
            w.startNanos = now
        }
        if (w.count >= limit) return false
        w.count++
        return true
    }
}

/**
 * Redis 백엔드(분산). `central.ratelimit.redis-enabled=true` 일 때만 빈으로 등록되어 @Primary 로 대체.
 * INCR + (최초 1회) EXPIRE 로 고정 윈도우를 인스턴스 간 공유한다.
 */
@Component
@Primary
@ConditionalOnProperty("central.ratelimit.redis-enabled")
class RedisRateLimitStore(
    private val redis: StringRedisTemplate,
) : RateLimitStore {
    override fun tryAcquire(
        key: String,
        limit: Int,
        windowSeconds: Long,
    ): Boolean {
        val redisKey = "rl:$key"
        val count = redis.opsForValue().increment(redisKey) ?: 1L
        if (count == 1L) {
            redis.expire(redisKey, windowSeconds, TimeUnit.SECONDS)
        }
        return count <= limit
    }
}
