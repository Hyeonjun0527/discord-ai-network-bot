package com.discordassistant.central.participation.adapter.outbound.rollout

import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.support.InMemoryActionScheduler
import com.discordassistant.central.actionruntime.support.MutableTestClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P18-T023: [ActionRuntimePendingCancellationAdapter] 가 강등된 길드의 pending 예약을 모두 취소하고 취소 건수를
 * 돌려준다(actionruntime 공개 purge 포트로 위임). 다른 길드의 예약은 건드리지 않는다.
 */
class ActionRuntimePendingCancellationAdapterTest {
    private val clock = MutableTestClock(Instant.parse("2026-06-22T00:00:00Z"))
    private val store = InMemoryActionScheduler(clock)
    private val adapter = ActionRuntimePendingCancellationAdapter(store)

    private fun schedule(
        decisionId: String,
        guild: String,
    ): ScheduledSocialAction {
        val action =
            ScheduledSocialAction.create(
                decisionId = decisionId,
                sampledActionIndex = 0,
                type = ScheduledActionType.SPEAK,
                target = ActionTarget(guildPseudonym = guild, channelId = "c1", threadId = "t1"),
                executeAfter = clock.instant().plusSeconds(60),
                contextVersion = 1,
            )
        store.schedule(action)
        return action
    }

    @Test
    fun `cancels all pending for the guild and returns the count`() {
        schedule("d-1", "g-1")
        schedule("d-2", "g-1")
        val other = schedule("d-3", "g-2")

        val cancelled = adapter.cancelPendingFor("g-1")

        assertThat(cancelled).isEqualTo(2)
        // 강등 길드의 pending 은 모두 종결돼 더는 조회되지 않는다.
        assertThat(store.findPendingIn(RevocationScope(guildPseudonym = "g-1"))).isEmpty()
        // 다른 길드는 그대로 pending.
        assertThat(store.findPendingIn(RevocationScope(guildPseudonym = "g-2"))).containsExactly(other.identity)
    }

    @Test
    fun `returns zero when the guild has no pending actions`() {
        assertThat(adapter.cancelPendingFor("g-empty")).isEqualTo(0)
    }
}
