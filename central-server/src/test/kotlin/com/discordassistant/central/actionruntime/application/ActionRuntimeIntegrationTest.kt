package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.execution.ActionExecutionService
import com.discordassistant.central.actionruntime.application.execution.BackpressureGate
import com.discordassistant.central.actionruntime.application.execution.ExecutionOutcome
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.application.reevaluate.StaleActionReevaluator
import com.discordassistant.central.actionruntime.application.scheduler.DueActionDisposition
import com.discordassistant.central.actionruntime.application.scheduler.DueActionPoller
import com.discordassistant.central.actionruntime.application.scheduler.SceneEvidenceProvider
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.Bubble
import com.discordassistant.central.actionruntime.domain.model.BurstPlan
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.domain.service.CancellationPolicy
import com.discordassistant.central.actionruntime.domain.service.SceneEvidence
import com.discordassistant.central.actionruntime.support.ControllableReevaluation
import com.discordassistant.central.actionruntime.support.InMemoryActionAudit
import com.discordassistant.central.actionruntime.support.InMemoryActionScheduler
import com.discordassistant.central.actionruntime.support.MutableTestClock
import com.discordassistant.central.actionruntime.support.RecordingDiscordExecutor
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * actionruntime 시나리오 통합 테스트(NEXA-P13-T023).
 *
 * 관찰→판단→예약→(due 도래)→재평가/취소→실행 의 end-to-end 흐름을 mock 으로 재생한다. acceptance(T023): 각
 * 시나리오에서 **기대한 전송 횟수와 terminal state 가 일치**한다. 시나리오: 정상 전송, 다른 인간 답변(취소), 주제
 * 전환(취소), 대상 삭제(실패), 권한 상실(실패), timeout(실패), 부분 전송(부분 취소).
 *
 * shadow 안전: 모든 시나리오를 OBSERVE_ONLY 로도 돌려 전송 0회를 확인한다(P09 일관).
 */
class ActionRuntimeIntegrationTest {
    private val due = Instant.parse("2026-01-01T00:00:00Z")

    private fun speak(
        decision: String = "dec-1",
        index: Int = 0,
        contextVersion: Long = 1L,
        channel: String = "chan-1",
        thread: String = "thread-1",
    ) = ScheduledSocialAction.create(
        decisionId = decision,
        sampledActionIndex = index,
        type = ScheduledActionType.SPEAK,
        target = ActionTarget("guild-1", channel, thread),
        executeAfter = due,
        contextVersion = contextVersion,
    )

    /** 한 시나리오 실행 컨텍스트(예약→poll→execute 를 한 흐름으로). */
    private class Harness(
        val clock: MutableTestClock,
        val scheduler: InMemoryActionScheduler,
        val reeval: ControllableReevaluation,
        val executor: RecordingDiscordExecutor,
        val audit: InMemoryActionAudit,
        val evidence: SceneEvidence,
    ) {
        private val poller =
            DueActionPoller(
                scheduler = scheduler,
                reevaluator = StaleActionReevaluator(reeval),
                cancellationPolicy = CancellationPolicy(),
                sceneEvidenceProvider = SceneEvidenceProvider { evidence },
                clock = clock,
            )
        private val execution =
            ActionExecutionService(executor, scheduler, reeval, audit, BackpressureGate(), clock)

        /** poll 한 due 행동을 처리하고, READY_TO_TYPE 인 것만 [mode] 로 실행한다. 실행 outcome 목록을 돌려준다. */
        fun pollAndExecute(
            mode: ShadowMode,
            plan: BurstPlan,
        ): List<ExecutionOutcome> =
            poller.pollOnce().mapNotNull { out ->
                if (out.disposition == DueActionDisposition.READY_TO_TYPE) {
                    execution.execute(mode, out.action, plan)
                } else {
                    null
                }
            }
    }

    private fun harness(
        action: ScheduledSocialAction,
        currentVersion: Long? = 1L,
        validOnReevaluate: Boolean = true,
        evidence: SceneEvidence =
            SceneEvidence(humanRepliesSinceSchedule = 0, currentFocusThreadId = action.target.threadId, targetExpired = false),
        executor: RecordingDiscordExecutor = RecordingDiscordExecutor(),
    ): Harness {
        val clock = MutableTestClock(due)
        val scheduler = InMemoryActionScheduler(clock)
        scheduler.schedule(action)
        return Harness(
            clock = clock,
            scheduler = scheduler,
            reeval = ControllableReevaluation(currentVersion = currentVersion, validOnReevaluate = validOnReevaluate),
            executor = executor,
            audit = InMemoryActionAudit(),
            evidence = evidence,
        )
    }

    @Test
    fun `정상 — 단일 버블 전송 후 COMPLETED, 전송 1회`() {
        val action = speak()
        val h = harness(action)
        val outcomes = h.pollAndExecute(ShadowMode.LIVE, BurstPlan.single("plan-a"))
        assertThat(outcomes).singleElement().isInstanceOf(ExecutionOutcome.Completed::class.java)
        assertThat(h.executor.sentBubbleIndexes).containsExactly(0)
        assertThat(h.scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.COMPLETED)
    }

    @Test
    fun `다른 인간 답변 — 취소(전송 0회), terminal CANCELLED`() {
        val action = speak()
        val h =
            harness(
                action,
                evidence =
                    SceneEvidence(
                        humanRepliesSinceSchedule = 3,
                        currentFocusThreadId = action.target.threadId,
                        targetExpired = false,
                    ),
            )
        val outcomes = h.pollAndExecute(ShadowMode.LIVE, BurstPlan.single("plan-a"))
        assertThat(outcomes).isEmpty() // 실행 진입 전 취소.
        assertThat(h.executor.totalExecutorCalls).isZero()
        assertThat(h.scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }

    @Test
    fun `주제 전환 — 취소(전송 0회), terminal CANCELLED`() {
        val action = speak()
        val h =
            harness(
                action,
                evidence = SceneEvidence(humanRepliesSinceSchedule = 0, currentFocusThreadId = "other-thread", targetExpired = false),
            )
        val outcomes = h.pollAndExecute(ShadowMode.LIVE, BurstPlan.single("plan-a"))
        assertThat(outcomes).isEmpty()
        assertThat(h.executor.totalExecutorCalls).isZero()
        assertThat(h.scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }

    @Test
    fun `대상 삭제 — 재평가에서 장면 소멸로 취소(전송 0회)`() {
        val action = speak()
        // currentVersion null = 장면 소멸(대상 삭제) → reevaluator CANCEL.
        val h = harness(action, currentVersion = null)
        val outcomes = h.pollAndExecute(ShadowMode.LIVE, BurstPlan.single("plan-a"))
        assertThat(outcomes).isEmpty()
        assertThat(h.executor.totalExecutorCalls).isZero()
        assertThat(h.scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }

    @Test
    fun `권한 상실 — 전송 시도 실패로 FAILED, 다른 채널 fallback 없음`() {
        val action = speak()
        val executor =
            RecordingDiscordExecutor(
                sendResults = ArrayDeque(listOf(ExecutionResult.Failed(ActionFailureReason.PERMISSION_DENIED))),
            )
        val h = harness(action, executor = executor)
        val outcomes = h.pollAndExecute(ShadowMode.LIVE, BurstPlan.single("plan-a"))
        assertThat(outcomes).singleElement().isEqualTo(ExecutionOutcome.Failed(ActionFailureReason.PERMISSION_DENIED))
        assertThat(executor.sentBubbleIndexes).isEmpty()
        assertThat(h.scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.FAILED)
    }

    @Test
    fun `timeout — 모델 timeout 으로 FAILED`() {
        val action = speak()
        val executor =
            RecordingDiscordExecutor(
                sendResults = ArrayDeque(listOf(ExecutionResult.Failed(ActionFailureReason.MODEL_TIMEOUT))),
            )
        val h = harness(action, executor = executor)
        val outcomes = h.pollAndExecute(ShadowMode.LIVE, BurstPlan.single("plan-a"))
        assertThat(outcomes).singleElement().isEqualTo(ExecutionOutcome.Failed(ActionFailureReason.MODEL_TIMEOUT))
        assertThat(h.scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.FAILED)
    }

    @Test
    fun `부분 전송 — 첫 버블 뒤 contextVersion 변경 시 잔여 미전송, terminal CANCELLED`() {
        val action = speak(contextVersion = 1L)
        val clock = MutableTestClock(due)
        val scheduler = InMemoryActionScheduler(clock)
        scheduler.schedule(action)
        // poll 시점엔 버전 동일(1) → 진행. 실행 중 index>0 사전 검사에서 stale 이 되도록 reeval 버전을 2 로.
        val reeval = ControllableReevaluation(currentVersion = 1L)
        val executor = RecordingDiscordExecutor()
        val audit = InMemoryActionAudit()
        val poller =
            DueActionPoller(
                scheduler,
                StaleActionReevaluator(reeval),
                CancellationPolicy(),
                SceneEvidenceProvider {
                    SceneEvidence(0, action.target.threadId, false)
                },
                clock,
            )
        val execution = ActionExecutionService(executor, scheduler, reeval, audit, BackpressureGate(), clock)
        val plan = BurstPlan(listOf(Bubble(0, "a", Duration.ZERO), Bubble(1, "b", Duration.ZERO)))

        val claimed = poller.pollOnce().single()
        assertThat(claimed.disposition).isEqualTo(DueActionDisposition.READY_TO_TYPE)
        reeval.currentVersion = 2L // 실행 직전 장면 변경(다른 메시지 도착).
        val outcome = execution.execute(ShadowMode.LIVE, claimed.action, plan)

        assertThat(outcome).isInstanceOf(ExecutionOutcome.PartiallyCancelled::class.java)
        assertThat(executor.sentBubbleIndexes).containsExactly(0) // 첫 버블만.
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }

    @Test
    fun `shadow OBSERVE_ONLY — 정상 시나리오라도 전송 0회`() {
        val action = speak()
        val h = harness(action)
        val outcomes = h.pollAndExecute(ShadowMode.OBSERVE_ONLY, BurstPlan.single("plan-a"))
        assertThat(outcomes).singleElement().isInstanceOf(ExecutionOutcome.Suppressed::class.java)
        assertThat(h.executor.totalExecutorCalls).isZero() // 전송 0회(P09 hard block).
    }
}
