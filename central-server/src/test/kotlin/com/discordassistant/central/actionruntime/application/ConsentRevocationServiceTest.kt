package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.application.privacy.ConsentRevocationService
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.support.InMemoryActionScheduler
import com.discordassistant.central.actionruntime.support.MutableTestClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P13-T014 — ConsentRevocationService: 동의 철회 시 다음 tick 을 기다리지 않는 즉시 취소 경로.
 */
class ConsentRevocationServiceTest {
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val scheduler = InMemoryActionScheduler(clock)
    private val service = ConsentRevocationService(scheduler)

    private fun schedule(
        index: Int,
        guild: String = "g1",
        channel: String = "c1",
        user: String? = "u1",
    ) {
        scheduler.schedule(
            ScheduledSocialAction.create(
                decisionId = "d$index",
                sampledActionIndex = index,
                type = ScheduledActionType.SPEAK,
                target = ActionTarget(guild, channel, "t1", subjectPseudonym = user),
                executeAfter = clock.instant(),
                contextVersion = 1,
            ),
        )
    }

    @Test
    fun `길드 전체 철회는 그 길드의 모든 pending 을 즉시 취소한다`() {
        schedule(0, guild = "g1", channel = "c1")
        schedule(1, guild = "g1", channel = "c2")
        schedule(2, guild = "g2", channel = "c1") // 다른 길드 — 영향 없음

        val cancelled = service.onConsentRevoked(RevocationScope(guildPseudonym = "g1"))

        assertThat(cancelled).isEqualTo(2)
        assertThat(scheduler.findPendingIn(RevocationScope("g1"))).isEmpty()
        assertThat(scheduler.findPendingIn(RevocationScope("g2"))).hasSize(1) // 다른 길드 보존
    }

    @Test
    fun `채널 범위 철회는 그 채널만 취소한다`() {
        schedule(0, channel = "c1")
        schedule(1, channel = "c2")

        val cancelled = service.onConsentRevoked(RevocationScope(guildPseudonym = "g1", channelId = "c1"))

        assertThat(cancelled).isEqualTo(1)
        assertThat(scheduler.find(ActionIdentity.of("d0", 0))!!.status).isEqualTo(ActionStatus.CANCELLED)
        // c2 는 보존.
        assertThat(scheduler.findPendingIn(RevocationScope("g1", channelId = "c2"))).hasSize(1)
    }

    @Test
    fun `사용자 범위 철회는 같은 채널에서도 그 사용자 pending 만 취소한다`() {
        schedule(0, channel = "c1", user = "u1")
        schedule(1, channel = "c1", user = "u2")
        schedule(2, channel = "c2", user = "u1")

        val cancelled = service.onConsentRevoked(RevocationScope(guildPseudonym = "g1", channelId = "c1", userPseudonym = "u1"))

        assertThat(cancelled).isEqualTo(1)
        assertThat(scheduler.find(ActionIdentity.of("d0", 0))!!.status).isEqualTo(ActionStatus.CANCELLED)
        assertThat(scheduler.find(ActionIdentity.of("d1", 1))!!.status).isEqualTo(ActionStatus.SCHEDULED)
        assertThat(scheduler.find(ActionIdentity.of("d2", 2))!!.status).isEqualTo(ActionStatus.SCHEDULED)
    }

    @Test
    fun `pending 이 없으면 0 을 돌려준다`() {
        assertThat(service.onConsentRevoked(RevocationScope("none"))).isEqualTo(0)
    }
}
