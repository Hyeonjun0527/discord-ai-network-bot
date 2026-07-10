package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.recovery.RecoveryDisposition
import com.discordassistant.central.actionruntime.application.recovery.RestartRecoveryService
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.support.InMemoryActionAudit
import com.discordassistant.central.actionruntime.support.InMemoryActionScheduler
import com.discordassistant.central.actionruntime.support.MutableTestClock
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * NEXA-P13-T010 — RestartRecoveryService: 만료 lease 회수 시 재시작으로 동일 버블이 두 번 전송되지 않음을 검증.
 */
class RestartRecoveryServiceTest {
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val scheduler = InMemoryActionScheduler(clock)
    private val audit = InMemoryActionAudit()
    private val service = RestartRecoveryService(scheduler, audit, clock)

    private fun action(
        status: ActionStatus,
        index: Int = 0,
    ) = ScheduledSocialAction(
        identity =
            com.discordassistant.central.actionruntime.domain.model.ActionIdentity
                .of("d1", index),
        decisionId = "d1",
        type = ScheduledActionType.SPEAK,
        target = ActionTarget("g1", "c1", "t1"),
        executeAfter = clock.instant(),
        contextVersion = 1,
        originRolloutMode = ShadowMode.LIVE,
        status = status,
    )

    @Test
    fun `만료 lease 가 없으면 복구할 것이 없다`() {
        assertThat(service.recoverOnStartup()).isEmpty()
    }

    @Test
    fun `TYPING in-flight 의 만료 lease 는 안전하게 재예약된다(본문 미전송)`() {
        val expired = clock.instant().minus(Duration.ofMinutes(1))
        scheduler.put(action(ActionStatus.TYPING, index = 0), leaseExpiresAt = expired)

        val recovered = service.recoverOnStartup()

        assertThat(recovered)
            .singleElement()
            .satisfies({ assertThat(it.disposition).isEqualTo(RecoveryDisposition.RESCHEDULED) })
        // 재예약되어 SCHEDULED 로 복귀 → 다시 due 처리 가능(유실 없음).
        assertThat(scheduler.find(action(ActionStatus.TYPING).identity)!!.status).isEqualTo(ActionStatus.SCHEDULED)
    }

    @Test
    fun `PARTIALLY_SENT 의 만료 lease 는 재전송 없이 종결된다(이중 전송 방지)`() {
        val expired = clock.instant().minus(Duration.ofMinutes(1))
        scheduler.put(action(ActionStatus.PARTIALLY_SENT, index = 1), leaseExpiresAt = expired)

        val recovered = service.recoverOnStartup()

        assertThat(recovered)
            .singleElement()
            .satisfies({ assertThat(it.disposition).isEqualTo(RecoveryDisposition.COMPLETED_NO_RESEND) })
        // COMPLETED 로 종결 → 같은 버블을 다시 보내지 않는다(T010 핵심).
        assertThat(scheduler.find(action(ActionStatus.PARTIALLY_SENT, index = 1).identity)!!.status)
            .isEqualTo(ActionStatus.COMPLETED)
        assertThat(audit.phasesOf(action(ActionStatus.PARTIALLY_SENT, index = 1).identity.value))
            .containsExactly(ActionAuditPhase.RECOVERED_NO_RESEND)
        assertThat(audit.findByAction(action(ActionStatus.PARTIALLY_SENT, index = 1).identity.value).single().reason)
            .isEqualTo("partial_recovery_no_resend")
    }

    @Test
    fun `아직 만료되지 않은 lease 는 회수하지 않는다`() {
        val future = clock.instant().plus(Duration.ofMinutes(5))
        scheduler.put(action(ActionStatus.TYPING), leaseExpiresAt = future)
        assertThat(service.recoverOnStartup()).isEmpty()
    }
}
