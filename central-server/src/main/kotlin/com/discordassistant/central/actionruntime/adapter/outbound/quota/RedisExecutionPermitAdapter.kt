package com.discordassistant.central.actionruntime.adapter.outbound.quota

import com.discordassistant.central.actionruntime.application.port.out.ExecutionLimits
import com.discordassistant.central.actionruntime.application.port.out.ExecutionPermitPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/** Redis Lua로 channel/global 카운터와 action reservation을 한 연산으로 예약한다. 장애 시 fail-closed한다. */
@Component
@ConditionalOnProperty(name = ["central.nexa.execution-permit.redis-enabled"], havingValue = "true", matchIfMissing = true)
class RedisExecutionPermitAdapter(
    private val redis: StringRedisTemplate,
) : ExecutionPermitPort {
    private val log = LoggerFactory.getLogger(RedisExecutionPermitAdapter::class.java)

    override fun reserve(
        actionId: String,
        channelKey: String,
        limits: ExecutionLimits,
    ): Boolean =
        try {
            redis.execute(
                RESERVE_SCRIPT,
                listOf(reservationKey(actionId), channelCounterKey(channelKey), GLOBAL_COUNTER_KEY),
                limits.perChannel.toString(),
                limits.global.toString(),
                limits.windowSeconds.toString(),
            ) == 1L
        } catch (e: DataAccessException) {
            log.warn("Redis 실행 permit 예약 실패 — fail-closed(action={}): {}", actionId, e.message)
            false
        }

    override fun release(actionId: String): Boolean =
        try {
            redis.execute(RELEASE_SCRIPT, listOf(reservationKey(actionId))) == 1L
        } catch (e: DataAccessException) {
            log.warn("Redis 실행 permit 해제 실패(action={}): {}", actionId, e.message)
            false
        }

    private fun reservationKey(actionId: String): String = "nexa:execution-permit:reservation:$actionId"

    private fun channelCounterKey(channelKey: String): String = "nexa:execution-permit:channel:$channelKey"

    private companion object {
        const val GLOBAL_COUNTER_KEY: String = "nexa:execution-permit:global"

        val RESERVE_SCRIPT =
            DefaultRedisScript(
                """
                if redis.call('EXISTS', KEYS[1]) == 1 then return 1 end
                local channelCount = tonumber(redis.call('GET', KEYS[2]) or '0')
                local globalCount = tonumber(redis.call('GET', KEYS[3]) or '0')
                if channelCount >= tonumber(ARGV[1]) or globalCount >= tonumber(ARGV[2]) then return 0 end
                local nextChannel = redis.call('INCR', KEYS[2])
                local nextGlobal = redis.call('INCR', KEYS[3])
                if nextChannel == 1 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
                if nextGlobal == 1 then redis.call('EXPIRE', KEYS[3], ARGV[3]) end
                redis.call('SET', KEYS[1], KEYS[2] .. '|' .. KEYS[3], 'EX', ARGV[3])
                return 1
                """.trimIndent(),
                Long::class.java,
            )

        val RELEASE_SCRIPT =
            DefaultRedisScript(
                """
                local value = redis.call('GET', KEYS[1])
                if not value then return 0 end
                local separator = string.find(value, '|', 1, true)
                if not separator then redis.call('DEL', KEYS[1]); return 0 end
                local channelKey = string.sub(value, 1, separator - 1)
                local globalKey = string.sub(value, separator + 1)
                if tonumber(redis.call('GET', channelKey) or '0') > 0 then redis.call('DECR', channelKey) end
                if tonumber(redis.call('GET', globalKey) or '0') > 0 then redis.call('DECR', globalKey) end
                redis.call('DEL', KEYS[1])
                return 1
                """.trimIndent(),
                Long::class.java,
            )
    }
}
