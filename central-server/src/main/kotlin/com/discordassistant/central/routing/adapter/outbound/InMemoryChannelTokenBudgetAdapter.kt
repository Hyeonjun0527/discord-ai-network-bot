package com.discordassistant.central.routing.adapter.outbound

import com.discordassistant.central.routing.application.ChannelTokenBudgetLimits
import com.discordassistant.central.routing.application.ChannelTokenBudgetPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** Redis를 명시적으로 끈 로컬 단일 인스턴스·테스트 환경의 채널 토큰 예산 저장소다. */
@Component
@ConditionalOnProperty(name = ["central.nexa.token-budget.redis-enabled"], havingValue = "false")
class InMemoryChannelTokenBudgetAdapter(
    private val clock: Clock = Clock.systemUTC(),
) : ChannelTokenBudgetPort {
    private data class Window(
        var tokens: Int,
        val expiresAt: Instant,
    )

    private data class Reservation(
        val channelKey: String,
        val estimatedTokens: Int,
        val expiresAt: Instant,
    )

    private val windows = mutableMapOf<String, Window>()
    private val reservations = mutableMapOf<String, Reservation>()

    @Synchronized
    override fun reserve(
        reservationId: String,
        channelKey: String,
        estimatedTokens: Int,
        limits: ChannelTokenBudgetLimits,
    ): Boolean {
        validate(reservationId, channelKey, estimatedTokens)
        val now = Instant.now(clock)
        prune(now)
        if (reservations.containsKey(reservationId)) return true
        val window = windows.getOrPut(channelKey) { Window(0, now.plusSeconds(limits.windowSeconds)) }
        if (window.tokens.toLong() + estimatedTokens > limits.perChannel) return false
        window.tokens += estimatedTokens
        reservations[reservationId] = Reservation(channelKey, estimatedTokens, window.expiresAt)
        return true
    }

    @Synchronized
    override fun settle(
        reservationId: String,
        actualTokens: Int,
    ): Boolean {
        require(actualTokens >= 0) { "실제 토큰 수는 음수일 수 없다" }
        val now = Instant.now(clock)
        prune(now)
        val reservation = reservations.remove(reservationId) ?: return false
        val window = windows[reservation.channelKey] ?: return false
        window.tokens =
            (window.tokens.toLong() - reservation.estimatedTokens + actualTokens)
                .coerceAtLeast(0)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        return true
    }

    @Synchronized
    internal fun usedTokens(channelKey: String): Int {
        prune(Instant.now(clock))
        return windows[channelKey]?.tokens ?: 0
    }

    private fun prune(now: Instant) {
        reservations.entries.removeIf { !now.isBefore(it.value.expiresAt) }
        windows.entries.removeIf { !now.isBefore(it.value.expiresAt) }
    }

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
    }
}
