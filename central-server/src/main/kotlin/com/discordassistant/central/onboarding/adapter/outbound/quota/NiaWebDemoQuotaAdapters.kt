package com.discordassistant.central.onboarding.adapter.outbound.quota

import com.discordassistant.central.onboarding.application.NiaWebDemoQuotaDecision
import com.discordassistant.central.onboarding.application.NiaWebDemoQuotaLimits
import com.discordassistant.central.onboarding.application.NiaWebDemoQuotaPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

@Component
@ConditionalOnProperty(
    name = ["central.nia-web-demo.redis-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RedisNiaWebDemoQuotaAdapter(
    private val redis: StringRedisTemplate,
) : NiaWebDemoQuotaPort {
    private val log = LoggerFactory.getLogger(RedisNiaWebDemoQuotaAdapter::class.java)

    override fun tryConsume(
        userId: String,
        limits: NiaWebDemoQuotaLimits,
    ): NiaWebDemoQuotaDecision {
        require(userId.isNotBlank()) { "웹 체험 사용자 ID가 비어 있습니다." }
        val userKey = userKey(userId)
        val result =
            try {
                redis.execute(
                    CONSUME_SCRIPT,
                    listOf(
                        "nia:web-demo:minute:$userKey",
                        "nia:web-demo:user:$userKey",
                        GLOBAL_COUNTER_KEY,
                    ),
                    limits.perMinute.toString(),
                    limits.perUserWindow.toString(),
                    limits.globalWindow.toString(),
                    limits.windowSeconds.toString(),
                )
            } catch (e: DataAccessException) {
                log.warn("니아 웹 체험 한도 확인 실패 — 비용 안전을 위해 요청 차단: {}", e.message)
                return NiaWebDemoQuotaDecision.Unavailable
            }
        return when {
            result == PER_MINUTE_EXCEEDED -> NiaWebDemoQuotaDecision.PerMinuteExceeded
            result == PER_USER_WINDOW_EXCEEDED -> NiaWebDemoQuotaDecision.PerUserWindowExceeded
            result == GLOBAL_WINDOW_EXCEEDED -> NiaWebDemoQuotaDecision.GlobalWindowExceeded
            result > 0 -> NiaWebDemoQuotaDecision.Allowed((result - ALLOWED_OFFSET).toInt())
            else -> NiaWebDemoQuotaDecision.Unavailable
        }
    }

    private fun userKey(userId: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(userId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val GLOBAL_COUNTER_KEY = "nia:web-demo:global"
        const val PER_MINUTE_EXCEEDED = -1L
        const val PER_USER_WINDOW_EXCEEDED = -2L
        const val GLOBAL_WINDOW_EXCEEDED = -3L
        const val ALLOWED_OFFSET = 1L

        val CONSUME_SCRIPT =
            DefaultRedisScript(
                """
                local minute = tonumber(redis.call('GET', KEYS[1]) or '0')
                local userWindow = tonumber(redis.call('GET', KEYS[2]) or '0')
                local globalWindow = tonumber(redis.call('GET', KEYS[3]) or '0')
                if minute >= tonumber(ARGV[1]) then return -1 end
                if userWindow >= tonumber(ARGV[2]) then return -2 end
                if globalWindow >= tonumber(ARGV[3]) then return -3 end
                local nextMinute = redis.call('INCR', KEYS[1])
                local nextUser = redis.call('INCR', KEYS[2])
                local nextGlobal = redis.call('INCR', KEYS[3])
                if nextMinute == 1 then redis.call('EXPIRE', KEYS[1], 60) end
                if nextUser == 1 then redis.call('EXPIRE', KEYS[2], ARGV[4]) end
                if nextGlobal == 1 then redis.call('EXPIRE', KEYS[3], ARGV[4]) end
                return tonumber(ARGV[2]) - nextUser + 1
                """.trimIndent(),
                Long::class.java,
            )
    }
}

@Component
@ConditionalOnProperty(name = ["central.nia-web-demo.redis-enabled"], havingValue = "false")
class InMemoryNiaWebDemoQuotaAdapter(
    private val clock: Clock = Clock.systemUTC(),
) : NiaWebDemoQuotaPort {
    private data class Window(
        var count: Int,
        val expiresAt: Instant,
    )

    private val windows = mutableMapOf<String, Window>()

    @Synchronized
    override fun tryConsume(
        userId: String,
        limits: NiaWebDemoQuotaLimits,
    ): NiaWebDemoQuotaDecision {
        require(userId.isNotBlank()) { "웹 체험 사용자 ID가 비어 있습니다." }
        val now = clock.instant()
        prune(now)
        val minute = window("minute:$userId", 60, now)
        val userWindow = window("user:$userId", limits.windowSeconds, now)
        val globalWindow = window("global", limits.windowSeconds, now)
        if (minute.count >= limits.perMinute) return NiaWebDemoQuotaDecision.PerMinuteExceeded
        if (userWindow.count >= limits.perUserWindow) return NiaWebDemoQuotaDecision.PerUserWindowExceeded
        if (globalWindow.count >= limits.globalWindow) return NiaWebDemoQuotaDecision.GlobalWindowExceeded
        minute.count++
        userWindow.count++
        globalWindow.count++
        return NiaWebDemoQuotaDecision.Allowed(limits.perUserWindow - userWindow.count)
    }

    private fun window(
        key: String,
        windowSeconds: Long,
        now: Instant,
    ): Window =
        windows.getOrPut(key) {
            Window(count = 0, expiresAt = now.plusSeconds(windowSeconds))
        }

    private fun prune(now: Instant) {
        windows.entries.removeIf { !now.isBefore(it.value.expiresAt) }
    }
}
