package com.discordassistant.central.actionruntime.adapter.inbound.scheduler

import com.discordassistant.central.actionruntime.adapter.outbound.multiresponse.MultiResponseBurstAdapter
import com.discordassistant.central.actionruntime.application.content.SpeechBurstContentCodec
import com.discordassistant.central.actionruntime.application.execution.ActionExecutionService
import com.discordassistant.central.actionruntime.application.execution.BackpressureGate
import com.discordassistant.central.actionruntime.application.execution.ReactionExecutionService
import com.discordassistant.central.actionruntime.application.port.out.ActionExecutionModePort
import com.discordassistant.central.actionruntime.application.port.out.SpeechContentResolver
import com.discordassistant.central.actionruntime.application.reevaluate.StaleActionReevaluator
import com.discordassistant.central.actionruntime.application.scheduler.DueActionPoller
import com.discordassistant.central.actionruntime.application.scheduler.SceneEvidenceProvider
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.domain.service.CancellationPolicy
import com.discordassistant.central.actionruntime.domain.service.SceneEvidence
import com.discordassistant.central.actionruntime.support.ControllableReevaluation
import com.discordassistant.central.actionruntime.support.InMemoryActionAudit
import com.discordassistant.central.actionruntime.support.InMemoryActionScheduler
import com.discordassistant.central.actionruntime.support.MutableTestClock
import com.discordassistant.central.actionruntime.support.RecordingDiscordExecutor
import com.discordassistant.central.participation.application.rollout.CanarySignalCollector
import com.discordassistant.central.participation.application.shadow.ShadowStatusService
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Instant

/**
 * NEXA-P13 자율 전송 오케스트레이터([AutonomousSendScheduler]) tick 통합 단위 테스트.
 *
 * 실제 [DueActionPoller]·[ActionExecutionService] 를 in-memory 지원물(스케줄러·executor·audit·시계)과 조립해,
 * due SPEAK 예약 하나에 대해:
 *  1. LIVE 로 게이팅되면 **단일 버블**로 실제 전송 경로가 돈다(전송 1건, COMPLETED).
 *  2. shadow(SHADOW_PREDICT)로 게이팅되면 [ActionExecutionService] 의 modePort 재확인이 executor 를 **한 번도**
 *     호출하지 않는다(전송 0 — P09 hard block). tick 이 LIVE 를 넘겨도 채널 모드가 우선한다.
 */
class AutonomousSendSchedulerTest {
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val scheduler = InMemoryActionScheduler(clock)
    private val burstAdapter = MultiResponseBurstAdapter()
    private val canarySignals = CanarySignalCollector(clock)

    private fun scheduleDueSpeak(originRolloutMode: ShadowMode = ShadowMode.LIVE): ScheduledSocialAction {
        val action =
            ScheduledSocialAction.create(
                decisionId = "dec-1",
                sampledActionIndex = 0,
                type = ScheduledActionType.SPEAK,
                target = ActionTarget(guildPseudonym = "g1", channelId = "123", threadId = "t1", routingChannelId = "123"),
                executeAfter = clock.instant().minusSeconds(1), // due
                contextVersion = 1,
                originRolloutMode = originRolloutMode,
            )
        scheduler.schedule(action)
        return action
    }

    private fun poller(): DueActionPoller =
        DueActionPoller(
            scheduler = scheduler,
            // currentVersion == 예약 버전(1) → not stale → PROCEED.
            reevaluator = StaleActionReevaluator(ControllableReevaluation(currentVersion = 1L)),
            cancellationPolicy = CancellationPolicy(),
            sceneEvidenceProvider = SceneEvidenceProvider { SceneEvidence(0, null, false) }, // KEEP
            clock = clock,
        )

    private fun executionService(
        executor: RecordingDiscordExecutor,
        modePort: ActionExecutionModePort,
    ): ActionExecutionService =
        ActionExecutionService(
            executor = executor,
            scheduler = scheduler,
            reevaluation = ControllableReevaluation(currentVersion = 1L),
            audit = InMemoryActionAudit(),
            backpressure = BackpressureGate(),
            clock = clock,
            modePort = modePort,
        )

    @Test
    fun `LIVE 게이팅이면 단일 버블로 전송하고 COMPLETED 로 종결한다`() {
        val action = scheduleDueSpeak()
        val executor = RecordingDiscordExecutor()
        // modePort 통과(예약 당시 LIVE 권한 그대로).
        val service = executionService(executor, ActionExecutionModePort.REQUESTED_MODE)
        val orchestrator =
            AutonomousSendScheduler(
                poller(),
                service,
                ReactionExecutionService(executor, scheduler, ControllableReevaluation(), InMemoryActionAudit(), clock),
                burstAdapter,
                SpeechContentResolver { SpeechBurstContentCodec.encode(listOf("단일 메시지")) },
                mock(ShadowStatusService::class.java),
                canarySignals,
            )

        orchestrator.tick()

        // 단일 버블만 전송(버블 index 0 하나) — 멀티 버블 burst 아님.
        assertThat(executor.sentBubbleIndexes).containsExactly(0)
        assertThat(executor.typingCalls).isEqualTo(1)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.COMPLETED)
        // 완료 전송은 canary 발화 신호로 집계된다(자동 중단 SAFETY NET 입력).
        assertThat(canarySignals.snapshot("g1").utterancesPerHour).isEqualTo(1)
    }

    @Test
    fun `shadow 게이팅이면 modePort 가 막아 전송이 0 이다(P09 hard block)`() {
        val action = scheduleDueSpeak()
        val executor = RecordingDiscordExecutor()
        // 채널 현재 모드가 SHADOW_PREDICT — 예약 당시 LIVE라도 실행 직전 재확인이 더 좁힌다.
        val service = executionService(executor) { _, _ -> ShadowMode.SHADOW_PREDICT }
        val orchestrator =
            AutonomousSendScheduler(
                poller(),
                service,
                ReactionExecutionService(executor, scheduler, ControllableReevaluation(), InMemoryActionAudit(), clock),
                burstAdapter,
                SpeechContentResolver { SpeechBurstContentCodec.encode(listOf("단일 메시지")) },
                mock(ShadowStatusService::class.java),
                canarySignals,
            )

        orchestrator.tick()

        // typing 포함 어떤 executor 호출도 없음(전송 0).
        assertThat(executor.totalExecutorCalls).isEqualTo(0)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }

    @Test
    fun `shadow에서 예약된 action은 이후 LIVE 승격만으로 전송되지 않는다`() {
        val action = scheduleDueSpeak(originRolloutMode = ShadowMode.SHADOW_PREDICT)
        val executor = RecordingDiscordExecutor()
        // 현재 채널은 LIVE로 승격됐지만, scheduler가 immutable shadow origin을 executor 경계에 전달해야 한다.
        val service = executionService(executor, ActionExecutionModePort.REQUESTED_MODE)
        val orchestrator =
            AutonomousSendScheduler(
                poller(),
                service,
                ReactionExecutionService(executor, scheduler, ControllableReevaluation(), InMemoryActionAudit(), clock),
                burstAdapter,
                SpeechContentResolver { SpeechBurstContentCodec.encode(listOf("단일 메시지")) },
                mock(ShadowStatusService::class.java),
                canarySignals,
            )

        orchestrator.tick()

        assertThat(executor.totalExecutorCalls).isEqualTo(0)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }

    @Test
    fun `multi bubble speech is executed as one Discord message per bubble`() {
        val action = scheduleDueSpeak()
        val executor = RecordingDiscordExecutor()
        val service = executionService(executor, ActionExecutionModePort.REQUESTED_MODE)
        val stored = SpeechBurstContentCodec.encode(listOf("첫 버블", "둘째 버블", "마지막 버블"))
        val orchestrator =
            AutonomousSendScheduler(
                poller(),
                service,
                ReactionExecutionService(executor, scheduler, ControllableReevaluation(), InMemoryActionAudit(), clock),
                burstAdapter,
                SpeechContentResolver { stored },
                mock(ShadowStatusService::class.java),
                canarySignals,
            )

        orchestrator.tick()

        assertThat(executor.sentBubbleIndexes).containsExactly(0, 1, 2)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.COMPLETED)
    }

    @Test
    fun `REACT 예약은 대상 메시지에 reaction을 실행하고 COMPLETED로 종결한다`() {
        val action =
            ScheduledSocialAction.create(
                decisionId = "dec-react",
                sampledActionIndex = 0,
                type = ScheduledActionType.REACT,
                target =
                    ActionTarget(
                        guildPseudonym = "g1",
                        channelId = "123",
                        threadId = "t1",
                        targetMessageId = "456",
                        routingChannelId = "123",
                    ),
                executeAfter = clock.instant().minusSeconds(1),
                contextVersion = 1,
                originRolloutMode = ShadowMode.LIVE,
                reactionCode = "unamused",
            )
        scheduler.schedule(action)
        val executor = RecordingDiscordExecutor()
        val speech = executionService(executor, ActionExecutionModePort.REQUESTED_MODE)
        val orchestrator =
            AutonomousSendScheduler(
                poller(),
                speech,
                ReactionExecutionService(executor, scheduler, ControllableReevaluation(), InMemoryActionAudit(), clock),
                burstAdapter,
                SpeechContentResolver { null },
                mock(ShadowStatusService::class.java),
                canarySignals,
            )

        orchestrator.tick()

        assertThat(executor.reactCalls).isEqualTo(1)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.COMPLETED)
        assertThat(executor.sentBubbleIndexes).isEmpty()
    }
}
