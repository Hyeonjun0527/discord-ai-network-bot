package com.discordassistant.central.routing.adapter.outbound

import com.discordassistant.central.routing.application.ChannelTokenBudgetLimits
import com.discordassistant.central.routing.application.ChannelTokenBudgetPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/** Redis Lua로 채널 토큰 예약·실사용 정산을 원자 처리한다. 저장소 장애 때는 비용 안전을 위해 fail-closed한다. */
@Component
@ConditionalOnProperty(
    name = ["central.nexa.token-budget.redis-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RedisChannelTokenBudgetAdapter(
    private val redis: StringRedisTemplate,
) : ChannelTokenBudgetPort {
    private val log = LoggerFactory.getLogger(RedisChannelTokenBudgetAdapter::class.java)

    override fun reserve(
        reservationId: String,
        channelKey: String,
        estimatedTokens: Int,
        limits: ChannelTokenBudgetLimits,
    ): Boolean {
        validate(reservationId, channelKey, estimatedTokens)
        return try {
            redis.execute(
                RESERVE_SCRIPT,
                listOf(reservationKey(reservationId), counterKey(channelKey)),
                estimatedTokens.toString(),
                limits.perChannel.toString(),
                limits.windowSeconds.toString(),
            ) == 1L
        } catch (e: DataAccessException) {
            log.warn("Redis 채널 토큰 예약 실패 — fail-closed: {}", e.message)
            false
        }
    }

    override fun settle(
        reservationId: String,
        actualTokens: Int,
    ): Boolean {
        require(actualTokens >= 0) { "실제 토큰 수는 음수일 수 없다" }
        return try {
            redis.execute(
                SETTLE_SCRIPT,
                listOf(reservationKey(reservationId)),
                actualTokens.toString(),
            ) == 1L
        } catch (e: DataAccessException) {
            log.warn("Redis 채널 토큰 정산 실패 — 보수적 예약량 유지: {}", e.message)
            false
        }
    }

    private fun reservationKey(reservationId: String): String = "nexa:channel-token-budget:reservation:$reservationId"

    private fun counterKey(channelKey: String): String = "nexa:channel-token-budget:channel:$channelKey"

    private fun validate(
        reservationId: String,
        channelKey: String,
        estimatedTokens: Int,
    ) {
        require(reservationId.matches(STABLE_KEY)) { "reservationId 형식이 잘못됐다" }
        require(channelKey.matches(STABLE_KEY)) { "channelKey 형식이 잘못됐다" }
        require(estimatedTokens > 0) { "예상 토큰 수는 양수여야 한다" }
    }

    private companion object {
        val STABLE_KEY = Regex("[A-Za-z0-9_:.=-]{1,200}")

        val RESERVE_SCRIPT =
            DefaultRedisScript(
                """
                if redis.call('EXISTS', KEYS[1]) == 1 then return 1 end
                local current = tonumber(redis.call('GET', KEYS[2]) or '0')
                local estimate = tonumber(ARGV[1])
                if current + estimate > tonumber(ARGV[2]) then return 0 end
                local nextValue = redis.call('INCRBY', KEYS[2], estimate)
                if nextValue == estimate then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
                redis.call('SET', KEYS[1], KEYS[2] .. '|' .. ARGV[1], 'EX', ARGV[3])
                return 1
                """.trimIndent(),
                Long::class.java,
            )

        val SETTLE_SCRIPT =
            DefaultRedisScript(
                """
                local value = redis.call('GET', KEYS[1])
                if not value then return 0 end
                local separator = string.find(value, '|', 1, true)
                if not separator then redis.call('DEL', KEYS[1]); return 0 end
                local counterKey = string.sub(value, 1, separator - 1)
                local estimated = tonumber(string.sub(value, separator + 1))
                local actual = tonumber(ARGV[1])
                local current = tonumber(redis.call('GET', counterKey) or '0')
                local settled = current - estimated + actual
                if settled < 0 then settled = 0 end
                if redis.call('EXISTS', counterKey) == 1 then redis.call('SET', counterKey, settled, 'KEEPTTL') end
                redis.call('DEL', KEYS[1])
                return 1
                """.trimIndent(),
                Long::class.java,
            )
    }
}
