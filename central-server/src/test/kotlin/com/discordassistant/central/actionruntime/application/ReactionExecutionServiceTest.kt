package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.execution.ReactionExecutionService
import com.discordassistant.central.actionruntime.application.execution.ReactionOutcome
import com.discordassistant.central.actionruntime.application.port.out.ActionConsentPort
import com.discordassistant.central.actionruntime.application.port.out.ActionExecutionModePort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionLimits
import com.discordassistant.central.actionruntime.application.port.out.ExecutionPermitPort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.support.ControllableReevaluation
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
                target = ActionTarget("guild-1", "chan-1", "thread-1", routingChannelId = "123"),
                executeAfter = Instant.parse("2026-01-01T00:00:00Z"),
                contextVersion = 1L,
                originRolloutMode = ShadowMode.LIVE,
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
            ReactionExecutionService(executor, scheduler, ControllableReevaluation(), audit, clock)
                .react(ShadowMode.LIVE, action, "target-msg", "👍")

        assertThat(outcome).isEqualTo(ReactionOutcome.Reacted)
        assertThat(executor.reactCalls).isEqualTo(1)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.COMPLETED)
    }

    @Test
    fun `실행 직전 동의가 철회되면 reaction을 호출하지 않는다`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = reactAction()
        scheduler.put(action)

        val outcome =
            ReactionExecutionService(
                executor,
                scheduler,
                ControllableReevaluation(),
                audit,
                clock,
                consent = ActionConsentPort { false },
            ).react(ShadowMode.LIVE, action, "target-msg", "👍")

        assertThat(outcome).isEqualTo(ReactionOutcome.Failed(ActionFailureReason.CONSENT_REVOKED))
        assertThat(executor.reactCalls).isZero()
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.FAILED)
    }

    @Test
    fun `T016 — reaction 실패가 SPEAK fallback 을 유발하지 않고 FAILED 로 종결한다`() {
        val executor = RecordingDiscordExecutor(reactResult = ExecutionResult.Failed(ActionFailureReason.PERMISSION_DENIED))
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = reactAction()
        scheduler.put(action)

        val outcome =
            ReactionExecutionService(executor, scheduler, ControllableReevaluation(), audit, clock)
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
            ReactionExecutionService(executor, scheduler, ControllableReevaluation(), audit, clock)
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
            ReactionExecutionService(executor, scheduler, ControllableReevaluation(), audit, clock, currentMode)
                .react(ShadowMode.LIVE, action, "target-msg", "👍")

        assertThat(outcome).isEqualTo(ReactionOutcome.Suppressed(ShadowMode.OFF))
        assertThat(executor.reactCalls).isZero()
        assertThat(audit.findByAction(action.identity.value).single().reason).isEqualTo(ShadowMode.OFF.name)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }

    @Test
    fun `reaction 실행 quota가 거부되면 Discord 호출 없이 실패한다`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = reactAction()
        scheduler.put(action)
        val permit =
            object : ExecutionPermitPort {
                override fun reserve(
                    actionId: String,
                    channelKey: String,
                    limits: ExecutionLimits,
                ): Boolean = false

                override fun release(actionId: String): Boolean = true
            }

        val outcome =
            ReactionExecutionService(
                executor,
                scheduler,
                ControllableReevaluation(),
                audit,
                clock,
                executionPermit = permit,
            ).react(ShadowMode.LIVE, action, "target-msg", "👍")

        assertThat(outcome).isEqualTo(ReactionOutcome.Failed(ActionFailureReason.EXECUTION_QUOTA_EXCEEDED))
        assertThat(executor.reactCalls).isZero()
        assertThat(scheduler.find(action.identity)?.status).isEqualTo(ActionStatus.FAILED)
    }

    @Test
    fun `Discord 호출 직전 장면이 바뀌면 permit을 반환하고 reaction을 취소한다`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = reactAction()
        scheduler.put(action)
        val reevaluation = ControllableReevaluation(currentVersion = 2L, validOnReevaluate = false)
        var reserved = 0
        var released = 0
        val permit =
            object : ExecutionPermitPort {
                override fun reserve(
                    actionId: String,
                    channelKey: String,
                    limits: ExecutionLimits,
                ): Boolean = true.also { reserved++ }

                override fun release(actionId: String): Boolean = true.also { released++ }
            }

        val outcome =
            ReactionExecutionService(
                executor,
                scheduler,
                reevaluation,
                audit,
                clock,
                executionPermit = permit,
            ).react(ShadowMode.LIVE, action, "target-msg", "👍")

        assertThat(outcome)
            .isEqualTo(ReactionOutcome.Cancelled(ReactionExecutionService.REASON_CONTEXT_CHANGED_BEFORE_REACT))
        assertThat(reserved).isEqualTo(1)
        assertThat(released).isEqualTo(1)
        assertThat(executor.reactCalls).isZero()
        assertThat(scheduler.find(action.identity)?.status).isEqualTo(ActionStatus.CANCELLED)
        assertThat(audit.findByAction(action.identity.value).single().reason)
            .isEqualTo(ReactionExecutionService.REASON_CONTEXT_CHANGED_BEFORE_REACT)
    }
}
