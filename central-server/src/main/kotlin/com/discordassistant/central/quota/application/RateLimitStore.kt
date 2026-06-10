package com.discordassistant.central.quota.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessException
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
    private val log = LoggerFactory.getLogger(RedisRateLimitStore::class.java)

    override fun tryAcquire(
        key: String,
        limit: Int,
        windowSeconds: Long,
    ): Boolean {
        val redisKey = "rl:$key"
        return try {
            val count = redis.opsForValue().increment(redisKey) ?: 1L
            if (count == 1L) {
                redis.expire(redisKey, windowSeconds, TimeUnit.SECONDS)
            }
            count <= limit
        } catch (e: DataAccessException) {
            // Redis 장애(연결 실패/타임아웃)가 모든 요청을 500 으로 떨구지 않도록 fail-open(허용)한다 —
            // rate limit 은 가용성 우선의 소프트 보호다(구체 예외만 잡고 숨기지 않는다, 예외 원칙 2·3).
            log.warn("Redis rate limit 조회 실패 — fail-open(요청 허용): key={} ({})", key, e.message)
            true
        }
    }
}
