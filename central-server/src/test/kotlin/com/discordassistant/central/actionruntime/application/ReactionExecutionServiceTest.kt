package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.execution.ReactionExecutionService
import com.discordassistant.central.actionruntime.application.execution.ReactionOutcome
import com.discordassistant.central.actionruntime.application.port.out.ActionExecutionModePort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.support.InMemoryActionAudit
import com.discordassistant.central.actionruntime.support.InMemoryActionScheduler
import com.discordassistant.central.actionruntime.support.MutableTestClock
import com.discordassistant.central.actionruntime.support.RecordingDiscordExecutor
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * REACT 실행 acceptance(NEXA-P13-T016): 권한·target·emoji 확인 실행 + **실패가 SPEAK fallback 을 유발하지 않는다**.
 */
class ReactionExecutionServiceTest {
    private val clock = MutableTestClock()

    private fun reactAction(): ScheduledSocialAction =
        ScheduledSocialAction
            .create(
                decisionId = "dec-react",
                sampledActionIndex = 0,
                type = ScheduledActionType.REACT,
                target = ActionTarget("guild-1", "chan-1", "thread-1"),
                executeAfter = Instant.parse("2026-01-01T00:00:00Z"),
                contextVersion = 1L,
            ).markScheduled()
            .beginReevaluation()
            .passReevaluation()

    @Test
    fun `T016 — reaction 성공이면 COMPLETED`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = reactAction()
        scheduler.put(action)

        val outcome =
            ReactionExecutionService(executor, scheduler, audit, clock)
                .react(ShadowMode.LIVE, action, "target-msg", "👍")

        assertThat(outcome).isEqualTo(ReactionOutcome.Reacted)
        assertThat(executor.reactCalls).isEqualTo(1)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.COMPLETED)
    }

    @Test
    fun `T016 — reaction 실패가 SPEAK fallback 을 유발하지 않고 FAILED 로 종결한다`() {
        val executor = RecordingDiscordExecutor(reactResult = ExecutionResult.Failed(ActionFailureReason.PERMISSION_DENIED))
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = reactAction()
        scheduler.put(action)

        val outcome =
            ReactionExecutionService(executor, scheduler, audit, clock)
                .react(ShadowMode.LIVE, action, "target-msg", "👍")

        assertThat(outcome).isEqualTo(ReactionOutcome.Failed(ActionFailureReason.PERMISSION_DENIED))
        // SPEAK fallback 없음 — 어떤 버블도 전송되지 않았다.
        assertThat(executor.sentBubbleIndexes).isEmpty()
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.FAILED)
        assertThat(audit.phasesOf(action.identity.value)).contains(ActionAuditPhase.FAILED)
    }

    @Test
    fun `P09 — OBSERVE_ONLY 는 reaction 을 0회 호출한다`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = reactAction()
        scheduler.put(action)

        val outcome =
            ReactionExecutionService(executor, scheduler, audit, clock)
                .react(ShadowMode.OBSERVE_ONLY, action, "target-msg", "👍")

        assertThat(outcome).isInstanceOf(ReactionOutcome.Suppressed::class.java)
        assertThat(executor.reactCalls).isZero()
        assertThat(audit.phasesOf(action.identity.value)).containsExactly(ActionAuditPhase.SUPPRESSED_SHADOW)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }

    @Test
    fun `T030 — 예약 시 LIVE 여도 실행 직전 현재 모드가 OFF 면 reaction 을 0회 호출한다`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = reactAction()
        scheduler.put(action)
        val currentMode = ActionExecutionModePort { _, _ -> ShadowMode.OFF }

        val outcome =
            ReactionExecutionService(executor, scheduler, audit, clock, currentMode)
                .react(ShadowMode.LIVE, action, "target-msg", "👍")

        assertThat(outcome).isEqualTo(ReactionOutcome.Suppressed(ShadowMode.OFF))
        assertThat(executor.reactCalls).isZero()
        assertThat(audit.findByAction(action.identity.value).single().reason).isEqualTo(ShadowMode.OFF.name)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }
}
