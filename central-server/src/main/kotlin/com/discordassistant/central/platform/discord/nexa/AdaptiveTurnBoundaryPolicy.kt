package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.participation.domain.service.AttentionGateConstants
import java.time.Duration
import java.time.Instant

/**
 * 연속 Discord 메시지를 한 번의 참여 판단으로 묶을 정적 경계를 계산한다.
 *
 * 최근 메시지 간격의 중앙값을 2~7초로 제한하고, 표본이 없으면 중간값을 사용한다. 타이핑은 경계를 늦출 수 있지만
 * 최초 메시지 기준 하드 마감은 넘지 않는다.
 */
internal class AdaptiveTurnBoundaryPolicy(
    private val minimumIdle: Duration = Duration.ofMillis(AttentionGateConstants.IDLE_MIN_MS.toLong()),
    private val maximumIdle: Duration = Duration.ofMillis(AttentionGateConstants.IDLE_MAX_MS.toLong()),
    private val hardMaximum: Duration = Duration.ofSeconds(30),
    private val typingGrace: Duration = Duration.ofMillis(AttentionGateConstants.TYPING_GRACE_MS.toLong()),
    val sampleLimit: Int = DEFAULT_SAMPLE_LIMIT,
    val sampleHorizon: Duration = hardMaximum,
) {
    init {
        require(!minimumIdle.isNegative && !minimumIdle.isZero) { "minimumIdle must be positive" }
        require(maximumIdle >= minimumIdle) { "maximumIdle must be at least minimumIdle" }
        require(hardMaximum >= maximumIdle) { "hardMaximum must be at least maximumIdle" }
        require(!typingGrace.isNegative && !typingGrace.isZero) { "typingGrace must be positive" }
        require(sampleLimit > 0) { "sampleLimit must be positive" }
        require(!sampleHorizon.isNegative && !sampleHorizon.isZero) { "sampleHorizon must be positive" }
    }

    fun deadline(
        firstMessageAt: Instant,
        lastMessageAt: Instant,
        recentGapMillis: Collection<Long>,
        typingUntil: Instant?,
    ): Instant {
        require(!lastMessageAt.isBefore(firstMessageAt)) { "lastMessageAt cannot precede firstMessageAt" }
        val hardDeadline = hardDeadline(firstMessageAt)
        val idleDeadline = lastMessageAt.plus(adaptiveIdle(recentGapMillis))
        val extendedDeadline =
            if (typingUntil != null && typingUntil.isAfter(idleDeadline)) {
                typingUntil
            } else {
                idleDeadline
            }
        return if (extendedDeadline.isAfter(hardDeadline)) hardDeadline else extendedDeadline
    }

    fun hardDeadline(firstMessageAt: Instant): Instant = firstMessageAt.plus(hardMaximum)

    fun typingUntil(
        typingAt: Instant,
        hardDeadline: Instant,
    ): Instant {
        val proposed = typingAt.plus(typingGrace)
        return if (proposed.isAfter(hardDeadline)) hardDeadline else proposed
    }

    fun adaptiveIdle(recentGapMillis: Collection<Long>): Duration {
        val median =
            recentGapMillis
                .filter { it >= 0 }
                .sorted()
                .let { values ->
                    when {
                        values.isEmpty() -> (minimumIdle.toMillis() + maximumIdle.toMillis()) / 2
                        values.size % 2 == 1 -> values[values.size / 2]
                        else -> averageWithoutOverflow(values[values.size / 2 - 1], values[values.size / 2])
                    }
                }
        return Duration.ofMillis(median.coerceIn(minimumIdle.toMillis(), maximumIdle.toMillis()))
    }

    private fun averageWithoutOverflow(
        left: Long,
        right: Long,
    ): Long = left + (right - left) / 2

    companion object {
        private const val DEFAULT_SAMPLE_LIMIT = 100
    }
}
