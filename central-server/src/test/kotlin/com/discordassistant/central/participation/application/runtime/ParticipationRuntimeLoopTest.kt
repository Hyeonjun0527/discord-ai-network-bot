package com.discordassistant.central.participation.application.runtime

import com.discordassistant.central.participation.domain.service.ChannelAttentionGate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ParticipationRuntimeLoopTest {
    private val scope = ParticipationRuntimeScope("guild_a", "channel_a", "thread_a")

    @Test
    fun `candidate message schedules idle reevaluation and idle tick consumes the deadline`() {
        val runtime = ParticipationRuntimeLoop()
        val decision = runtime.onMessage(message(tsMs = 1_000))

        assertThat(decision).isInstanceOf(ParticipationRuntimeDecision.ScheduleIdleReevaluation::class.java)
        val scheduled = decision as ParticipationRuntimeDecision.ScheduleIdleReevaluation
        assertThat(scheduled.reasonCode).isEqualTo("CHUNK_END")
        assertThat(scheduled.deadlineMs).isEqualTo(5_500)

        assertThat(runtime.onIdleTick(scope, scheduled.deadlineMs - 1))
            .isEqualTo(ParticipationRuntimeDecision.NoWake("IDLE_NOT_DUE"))
        assertThat(runtime.onIdleTick(scope, scheduled.deadlineMs))
            .isEqualTo(ParticipationRuntimeDecision.EvaluateNow("IDLE_DUE"))
        assertThat(runtime.onIdleTick(scope, scheduled.deadlineMs + 1))
            .isEqualTo(ParticipationRuntimeDecision.NoWake("IDLE_NOT_DUE"))
    }

    @Test
    fun `direct address pressure is accumulated from thread state instead of repeated-demand regex`() {
        val runtime = ParticipationRuntimeLoop()

        val first =
            runtime.onMessage(
                message(
                    tsMs = 1_000,
                    directAddressed = true,
                    nicknameCall = true,
                    previousIgnoredRequestCount = 1,
                ),
            )
        val second =
            runtime.onMessage(
                message(
                    tsMs = 3_000,
                    directAddressed = true,
                    previousIgnoredRequestCount = 2,
                ),
            )

        assertThat(first.signals.directAddressPressure).isCloseTo(0.45, within(1e-9))
        assertThat(second.signals.directAddressPressure).isGreaterThan(first.signals.directAddressPressure)
        assertThat(second.signals.previousIgnoredRequestCount).isEqualTo(2)
        assertThat(first.signals.nicknameCall).isTrue()
        assertThat(first.signals.niaAddressedOrIdleOpportunity).isTrue()
    }

    @Test
    fun `human to human reply is passed as turn taking evidence without becoming a direct address`() {
        val runtime = ParticipationRuntimeLoop()
        val decision =
            runtime.onMessage(
                message(
                    tsMs = 1_000,
                    replyToHuman = true,
                    humansTalkingToEachOtherLikely = false,
                ),
            )

        assertThat(decision.signals.humansTalkingToEachOtherLikely).isTrue()
        assertThat(decision.signals.niaAddressedOrIdleOpportunity).isFalse()
    }

    @Test
    fun `pending action wake cancels stale target and reevaluates changed context`() {
        val runtime = ParticipationRuntimeLoop()
        val pending = ParticipationRuntimePendingAction("pending-1", scheduledContextVersion = 10)

        assertThat(
            runtime.onPendingActionWake(
                pending,
                ParticipationRuntimeLatestScene(
                    contextVersion = 10,
                    humanRepliesSinceSchedule = 2,
                    resolvedLikely = false,
                    targetExpired = false,
                ),
            ),
        ).isEqualTo(ParticipationRuntimeDecision.CancelPending("pending-1", "OTHER_HUMAN_ANSWERED"))
        assertThat(
            runtime.onPendingActionWake(
                pending,
                ParticipationRuntimeLatestScene(
                    contextVersion = 10,
                    humanRepliesSinceSchedule = 0,
                    resolvedLikely = true,
                    targetExpired = false,
                ),
            ),
        ).isEqualTo(ParticipationRuntimeDecision.CancelPending("pending-1", "ALREADY_RESOLVED"))
        assertThat(
            runtime.onPendingActionWake(
                pending,
                ParticipationRuntimeLatestScene(
                    contextVersion = 11,
                    humanRepliesSinceSchedule = 0,
                    resolvedLikely = false,
                    targetExpired = false,
                ),
            ),
        ).isEqualTo(ParticipationRuntimeDecision.ReevaluatePending("pending-1", "CONTEXT_VERSION_CHANGED"))
    }

    @Test
    fun `rate limit and anti spam pressure are judge inputs not runtime hard drops`() {
        val runtime = ParticipationRuntimeLoop()
        val decision =
            runtime.onMessage(
                message(
                    tsMs = 1_000,
                    rateLimitPressure = 1.0,
                    antiSpamPressure = 0.8,
                ),
            )

        assertThat(decision).isNotInstanceOf(ParticipationRuntimeDecision.NoWake::class.java)
        assertThat(decision.signals.rateLimitPressure).isEqualTo(1.0)
        assertThat(decision.signals.antiSpamPressure).isEqualTo(0.8)
    }

    @Test
    fun `invalid runtime inputs fail fast`() {
        assertThatThrownBy { ParticipationRuntimeScope("guild", " ", "thread") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { message(tsMs = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { message(replyChainDepth = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { message(rateLimitPressure = 1.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun message(
        tsMs: Long = 1_000,
        isNia: Boolean = false,
        hardPolicy: String? = ChannelAttentionGate.HARD_CANDIDATE,
        directAddressed: Boolean = false,
        replyToNia: Boolean = false,
        replyToHuman: Boolean = false,
        conversationMentionsNia: Boolean = false,
        nicknameCall: Boolean = false,
        replyChainDepth: Int = 0,
        previousIgnoredRequestCount: Int = 0,
        humansTalkingToEachOtherLikely: Boolean = false,
        rateLimitPressure: Double = 0.0,
        antiSpamPressure: Double = 0.0,
    ): ParticipationRuntimeMessageEvent =
        ParticipationRuntimeMessageEvent(
            scope = scope,
            tsMs = tsMs,
            isNia = isNia,
            hardPolicy = hardPolicy,
            directAddressed = directAddressed,
            replyToNia = replyToNia,
            replyToHuman = replyToHuman,
            conversationMentionsNia = conversationMentionsNia,
            nicknameCall = nicknameCall,
            replyChainDepth = replyChainDepth,
            previousIgnoredRequestCount = previousIgnoredRequestCount,
            humansTalkingToEachOtherLikely = humansTalkingToEachOtherLikely,
            rateLimitPressure = rateLimitPressure,
            antiSpamPressure = antiSpamPressure,
        )
}
