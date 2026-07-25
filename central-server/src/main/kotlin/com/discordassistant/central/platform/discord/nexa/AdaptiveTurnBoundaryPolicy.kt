package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.participation.domain.service.AttentionGateConstants
import java.time.Duration
import java.time.Instant

/**
 * Calculates the quiet boundary used to collapse a burst of Discord messages into one participation judgment.
 *
 * The idle window follows the median observed message gap and is bounded to a human-scale 2–7 seconds. The first
 * message uses the midpoint because no channel tempo has been observed yet. Typing can postpone the boundary, but the
 * first-message hard deadline always wins.
 */
internal class AdaptiveTurnBoundaryPolicy(
    private val minimumIdle: Duration = Duration.ofMillis(AttentionGateConstants.IDLE_MIN_MS.toLong()),
    private val maximumIdle: Duration = Duration.ofMillis(AttentionGateConstants.IDLE_MAX_MS.toLong()),
    private val hardMaximum: Duration = Duration.ofSeconds(30),
    private val typingGrace: Duration = Duration.ofMillis(AttentionGateConstants.TYPING_GRACE_MS.toLong()),
    val sampleLimit: Int = AttentionGateConstants.GAP_WINDOW,
) {
    init {
        require(!minimumIdle.isNegative && !minimumIdle.isZero) { "minimumIdle must be positive" }
        require(maximumIdle >= minimumIdle) { "maximumIdle must be at least minimumIdle" }
        require(hardMaximum >= maximumIdle) { "hardMaximum must be at least maximumIdle" }
        require(!typingGrace.isNegative && !typingGrace.isZero) { "typingGrace must be positive" }
        require(sampleLimit > 0) { "sampleLimit must be positive" }
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
}
