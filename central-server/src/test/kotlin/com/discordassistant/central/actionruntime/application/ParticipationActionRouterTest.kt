package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.ClaimedAction
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.participation.domain.model.action.ReactionCode
import com.discordassistant.central.participation.domain.model.action.SocialAction
import com.discordassistant.central.participation.domain.model.action.SpeechRequestRef
import com.discordassistant.central.participation.domain.model.decision.ActionDelay
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P15-T006 participation 결정 → actionruntime 예약 라우팅 단위 테스트(fake scheduler).
 *
 * 핵심 acceptance: IGNORE/WAIT 는 예약 없음(전송·quota 무소모), REACT/SPEAK 는 각 경로로 예약.
 */
class ParticipationActionRouterTest {
    private val scheduler = FakeScheduler()
    private val router = ParticipationActionRouter(scheduler)
    private val target = ActionTarget(guildPseudonym = "g-1", channelId = "100", threadId = "t-1")

    @Test
    fun `acceptance — IGNORE 는 예약하지 않는다`() {
        val result = router.route("d-1", 0, SocialAction.Ignore, target, Instant.now(), contextVersion = 1)
        assertThat(result).isInstanceOf(RouteResult.Ignored::class.java)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `acceptance — WAIT 도 예약하지 않는다`() {
        val result = router.route("d-1", 0, SocialAction.Wait(ActionDelay.IMMEDIATE), target, Instant.now(), 1)
        assertThat(result).isInstanceOf(RouteResult.Ignored::class.java)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `acceptance — REACT 는 REACT 예약`() {
        val react = SocialAction.React(reactionCodes = listOf(ReactionCode("thumbs_up")))
        val result = router.route("d-1", 0, react, target, Instant.now(), 1)
        assertThat(result).isEqualTo(RouteResult.Scheduled(ScheduledActionType.REACT, newlyScheduled = true))
        assertThat(scheduler.scheduled).hasSize(1)
        assertThat(scheduler.scheduled.first().type).isEqualTo(ScheduledActionType.REACT)
    }

    @Test
    fun `acceptance — SPEAK 는 SPEAK 예약`() {
        val speak = SocialAction.Speak(speechRequest = SpeechRequestRef(correlationId = "d-1"))
        val result = router.route("d-1", 0, speak, target, Instant.now(), 1)
        assertThat(result).isEqualTo(RouteResult.Scheduled(ScheduledActionType.SPEAK, newlyScheduled = true))
        assertThat(scheduler.scheduled.first().type).isEqualTo(ScheduledActionType.SPEAK)
    }

    @Test
    fun `멱등 — 같은 결정 재예약은 newlyScheduled=false`() {
        scheduler.rejectDuplicate = true
        val speak = SocialAction.Speak(speechRequest = SpeechRequestRef(correlationId = "d-1"))
        val result = router.route("d-1", 0, speak, target, Instant.now(), 1)
        assertThat(result).isEqualTo(RouteResult.Scheduled(ScheduledActionType.SPEAK, newlyScheduled = false))
    }

    private class FakeScheduler : ActionSchedulerPort {
        val scheduled = mutableListOf<ScheduledSocialAction>()
        var rejectDuplicate = false

        override fun schedule(action: ScheduledSocialAction): Boolean {
            scheduled.add(action)
            return !rejectDuplicate
        }

        override fun claimDue(
            now: Instant,
            leaseExpiresAt: Instant,
            limit: Int,
        ): List<ClaimedAction> = emptyList()

        override fun reclaimExpiredLeases(now: Instant): List<ActionIdentity> = emptyList()

        override fun reschedule(
            identity: ActionIdentity,
            executeAfter: Instant,
            attempt: Int,
        ): Boolean = true

        override fun markTyping(identity: ActionIdentity): Boolean = true

        override fun markPartiallySent(identity: ActionIdentity): Boolean = true

        override fun cancel(identity: ActionIdentity): Boolean = true

        override fun complete(identity: ActionIdentity): Boolean = true

        override fun fail(
            identity: ActionIdentity,
            reason: ActionFailureReason,
        ): Boolean = true

        override fun find(identity: ActionIdentity): ScheduledSocialAction? = null
    }
}
