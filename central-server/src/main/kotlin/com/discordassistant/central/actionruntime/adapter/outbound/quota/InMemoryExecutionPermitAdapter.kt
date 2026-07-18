package com.discordassistant.central.actionruntime.adapter.outbound.quota

import com.discordassistant.central.actionruntime.application.port.out.ExecutionLimits
import com.discordassistant.central.actionruntime.application.port.out.ExecutionPermitPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** 명시적으로 Redis를 끈 단일 인스턴스·테스트 환경 전용 원자적 실행 permit 저장소다. */
@Component
@ConditionalOnProperty(name = ["central.nexa.execution-permit.redis-enabled"], havingValue = "false", matchIfMissing = false)
class InMemoryExecutionPermitAdapter(
    private val clock: Clock = Clock.systemUTC(),
) : ExecutionPermitPort {
    private data class Window(
        var count: Int,
        val expiresAt: Instant,
    )

    private data class Reservation(
        val channelCounterKey: String,
        val globalCounterKey: String,
        val expiresAt: Instant,
    )

    private val counters = mutableMapOf<String, Window>()
    private val reservations = mutableMapOf<String, Reservation>()

    @Synchronized
    override fun reserve(
        actionId: String,
        channelKey: String,
        limits: ExecutionLimits,
    ): Boolean {
        require(actionId.isNotBlank()) { "actionId 는 비어 있을 수 없다" }
        val now = Instant.now(clock)
        prune(now)
        if (reservations.containsKey(actionId)) return true
        val channelCounterKey = "channel:$channelKey"
        val globalCounterKey = "global"
        val channel = counter(channelCounterKey, now, limits.windowSeconds)
        val global = counter(globalCounterKey, now, limits.windowSeconds)
        if (channel.count >= limits.perChannel || global.count >= limits.global) return false
        channel.count++
        global.count++
        reservations[actionId] = Reservation(channelCounterKey, globalCounterKey, minOf(channel.expiresAt, global.expiresAt))
        return true
    }

    @Synchronized
    override fun release(actionId: String): Boolean {
        val reservation = reservations.remove(actionId) ?: return false
        counters[reservation.channelCounterKey]?.let { it.count = (it.count - 1).coerceAtLeast(0) }
        counters[reservation.globalCounterKey]?.let { it.count = (it.count - 1).coerceAtLeast(0) }
        return true
    }

    private fun counter(
        key: String,
        now: Instant,
        windowSeconds: Long,
    ): Window = counters.getOrPut(key) { Window(0, now.plusSeconds(windowSeconds)) }

    private fun prune(now: Instant) {
        reservations.entries.removeIf { !now.isBefore(it.value.expiresAt) }
        counters.entries.removeIf { !now.isBefore(it.value.expiresAt) }
    }
}
