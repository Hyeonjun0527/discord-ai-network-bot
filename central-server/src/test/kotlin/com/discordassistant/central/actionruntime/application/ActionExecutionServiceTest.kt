package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.execution.ActionExecutionService
import com.discordassistant.central.actionruntime.application.execution.BackpressureGate
import com.discordassistant.central.actionruntime.application.execution.ExecutionOutcome
import com.discordassistant.central.actionruntime.application.port.out.ActionExecutionModePort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.Bubble
import com.discordassistant.central.actionruntime.domain.model.BurstPlan
import com.discordassistant.central.actionruntime.domain.service.BackpressurePolicy
import com.discordassistant.central.actionruntime.support.ControllableReevaluation
import com.discordassistant.central.actionruntime.support.InMemoryActionAudit
import com.discordassistant.central.actionruntime.support.InMemoryActionScheduler
import com.discordassistant.central.actionruntime.support.MutableTestClock
import com.discordassistant.central.actionruntime.support.RecordingDiscordExecutor
import com.discordassistant.central.actionruntime.support.typingSpeakAction
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 예약 행동 실행 orchestration acceptance(NEXA-P13-T015/T017/T018/T020/T021 + P09 shadow hard block).
 */
class ActionExecutionServiceTest {
    private val clock = MutableTestClock()

    private fun service(
        executor: RecordingDiscordExecutor,
        scheduler: InMemoryActionScheduler,
        reeval: ControllableReevaluation,
        audit: InMemoryActionAudit,
        backpressure: BackpressureGate = BackpressureGate(),
        modePort: ActionExecutionModePort = ActionExecutionModePort.REQUESTED_MODE,
    ) = ActionExecutionService(executor, scheduler, reeval, audit, backpressure, clock, modePort)

    @Test
    fun `T017 — 모든 버블 전송 후 COMPLETED 이고 message ID 가 audit 에 연결된다`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val reeval = ControllableReevaluation(currentVersion = 1L)
        val audit = InMemoryActionAudit()
        val action = typingSpeakAction(contextVersion = 1L)
        scheduler.put(action)
        val plan =
            BurstPlan(
                listOf(
                    Bubble(0, "plan-a", Duration.ZERO),
                    Bubble(1, "plan-b", Duration.ZERO),
                ),
            )

        val outcome = service(executor, scheduler, reeval, audit).execute(ShadowMode.LIVE, action, plan)

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Completed::class.java)
        assertThat(executor.sentBubbleIndexes).containsExactly(0, 1)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.COMPLETED)
        // SENT audit 가 전송한 메시지 ID 를 싣는다(T017/T022).
        val sent = audit.findByAction(action.identity.value).filter { it.phase == ActionAuditPhase.SENT }
        assertThat(sent.map { it.messageId }).containsExactlyElementsOf(executor.sentMessageIds)
        assertThat(audit.phasesOf(action.identity.value)).endsWith(ActionAuditPhase.COMPLETED)
    }

    @Test
    fun `T015 — typing 은 전송 전 시작되고 종결 시 반드시 종료된다(typing 만 남는 상태 없음)`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = typingSpeakAction()
        scheduler.put(action)

        service(executor, scheduler, ControllableReevaluation(), audit)
            .execute(ShadowMode.LIVE, action, BurstPlan.single("plan-a"))

        val phases = audit.phasesOf(action.identity.value)
        // typing 시작이 전송 전, 그리고 종결 시 typing 종료가 반드시 기록된다(무한 typing 방지 — T015).
        assertThat(phases).containsSubsequence(
            ActionAuditPhase.TYPING_STARTED,
            ActionAuditPhase.SENT,
            ActionAuditPhase.TYPING_STOPPED,
            ActionAuditPhase.COMPLETED,
        )
        assertThat(executor.typingCalls).isEqualTo(1)
    }

    @Test
    fun `T015 — 부분 취소 시에도 typing 이 종료된다`() {
        val scheduler = InMemoryActionScheduler(clock)
        val reeval = ControllableReevaluation(currentVersion = 1L, validOnReevaluate = false)
        val executor =
            object : RecordingDiscordExecutor() {
                override fun sendBubble(
                    channelId: String,
                    speechPlanRef: String,
                    bubbleIndex: Int,
                    replyToMessageId: String?,
                ): ExecutionResult {
                    val result = super.sendBubble(channelId, speechPlanRef, bubbleIndex, replyToMessageId)
                    if (bubbleIndex == 0) reeval.currentVersion = 2L
                    return result
                }
            }
        val audit = InMemoryActionAudit()
        val action = typingSpeakAction(contextVersion = 1L)
        scheduler.put(action)
        val plan = BurstPlan(listOf(Bubble(0, "a", Duration.ZERO), Bubble(1, "b", Duration.ZERO)))

        service(executor, scheduler, reeval, audit).execute(ShadowMode.LIVE, action, plan)

        // 취소 경로에서도 TYPING_STOPPED 가 남는다(typing 만 남는 상태 없음).
        assertThat(audit.phasesOf(action.identity.value)).contains(ActionAuditPhase.TYPING_STOPPED)
    }

    @Test
    fun `P09 — OBSERVE_ONLY 는 executor 를 0회 호출하고 SUPPRESSED_SHADOW 만 audit 한다`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val reeval = ControllableReevaluation()
        val audit = InMemoryActionAudit()
        val action = typingSpeakAction()
        scheduler.put(action)

        val outcome =
            service(executor, scheduler, reeval, audit)
                .execute(ShadowMode.OBSERVE_ONLY, action, BurstPlan.single("plan-a"))

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Suppressed::class.java)
        assertThat(executor.totalExecutorCalls).isZero() // 전송 0회 — typing 포함 어떤 JDA 호출도 없음.
        assertThat(audit.phasesOf(action.identity.value)).containsExactly(ActionAuditPhase.SUPPRESSED_SHADOW)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }

    @Test
    fun `P09 — OFF·SHADOW_PREDICT 도 전송 0회`() {
        for (mode in listOf(ShadowMode.OFF, ShadowMode.SHADOW_PREDICT)) {
            val executor = RecordingDiscordExecutor()
            val scheduler = InMemoryActionScheduler(clock)
            val action = typingSpeakAction()
            scheduler.put(action)
            service(executor, scheduler, ControllableReevaluation(), InMemoryActionAudit())
                .execute(mode, action, BurstPlan.single("plan-a"))
            assertThat(executor.totalExecutorCalls).isZero()
            assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
        }
    }

    @Test
    fun `T030 — 예약 시 LIVE 여도 실행 직전 현재 모드가 SHADOW 면 전송하지 않는다`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = typingSpeakAction()
        scheduler.put(action)
        val currentMode = ActionExecutionModePort { _, _ -> ShadowMode.SHADOW_PREDICT }

        val outcome =
            service(
                executor = executor,
                scheduler = scheduler,
                reeval = ControllableReevaluation(),
                audit = audit,
                modePort = currentMode,
            ).execute(ShadowMode.LIVE, action, BurstPlan.single("plan-a"))

        assertThat(outcome).isEqualTo(ExecutionOutcome.Suppressed(ShadowMode.SHADOW_PREDICT))
        assertThat(executor.totalExecutorCalls).isZero()
        assertThat(audit.findByAction(action.identity.value).single().reason).isEqualTo(ShadowMode.SHADOW_PREDICT.name)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
    }

    @Test
    fun `T018 — 권한 상실이면 다른 채널 fallback 없이 FAILED 로 우아하게 종결한다`() {
        val executor =
            RecordingDiscordExecutor(
                sendResults = ArrayDeque(listOf(ExecutionResult.Failed(ActionFailureReason.PERMISSION_DENIED))),
            )
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = typingSpeakAction()
        scheduler.put(action)

        val outcome =
            service(executor, scheduler, ControllableReevaluation(), audit)
                .execute(ShadowMode.LIVE, action, BurstPlan.single("plan-a"))

        assertThat(outcome).isEqualTo(ExecutionOutcome.Failed(ActionFailureReason.PERMISSION_DENIED))
        assertThat(executor.sentBubbleIndexes).isEmpty() // 전송된 버블 없음(다른 채널 시도 없음).
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.FAILED)
        assertThat(audit.phasesOf(action.identity.value)).contains(ActionAuditPhase.FAILED)
    }

    @Test
    fun `T018 — typing 단계에서 대상 부재면 전송 시도 없이 FAILED`() {
        val executor =
            RecordingDiscordExecutor(typingResult = ExecutionResult.Failed(ActionFailureReason.TARGET_MISSING))
        val scheduler = InMemoryActionScheduler(clock)
        val action = typingSpeakAction()
        scheduler.put(action)

        val outcome =
            service(executor, scheduler, ControllableReevaluation(), InMemoryActionAudit())
                .execute(ShadowMode.LIVE, action, BurstPlan.single("plan-a"))

        assertThat(outcome).isEqualTo(ExecutionOutcome.Failed(ActionFailureReason.TARGET_MISSING))
        assertThat(executor.sentBubbleIndexes).isEmpty()
    }

    @Test
    fun `전송 직전 contextVersion이 바뀌면 typing과 첫 버블 없이 취소한다`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val reeval = ControllableReevaluation(currentVersion = 2L, validOnReevaluate = false)
        val audit = InMemoryActionAudit()
        val action = typingSpeakAction(contextVersion = 1L)
        scheduler.put(action)

        val outcome = service(executor, scheduler, reeval, audit).execute(ShadowMode.LIVE, action, BurstPlan.single("plan-a"))

        assertThat(outcome).isEqualTo(ExecutionOutcome.Cancelled(ActionExecutionService.REASON_CONTEXT_CHANGED_BEFORE_SEND))
        assertThat(executor.totalExecutorCalls).isZero()
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
        assertThat(audit.phasesOf(action.identity.value)).containsExactly(ActionAuditPhase.CANCELLED)
    }

    @Test
    fun `T020 — 첫 버블 뒤 contextVersion 이 바뀌면 잔여 버블을 보내지 않고 부분 취소한다`() {
        val scheduler = InMemoryActionScheduler(clock)
        val reeval = ControllableReevaluation(currentVersion = 1L, validOnReevaluate = false)
        val executor =
            object : RecordingDiscordExecutor() {
                override fun sendBubble(
                    channelId: String,
                    speechPlanRef: String,
                    bubbleIndex: Int,
                    replyToMessageId: String?,
                ): ExecutionResult {
                    val result = super.sendBubble(channelId, speechPlanRef, bubbleIndex, replyToMessageId)
                    if (bubbleIndex == 0) reeval.currentVersion = 2L
                    return result
                }
            }
        val audit = InMemoryActionAudit()
        val action = typingSpeakAction(contextVersion = 1L)
        scheduler.put(action)
        val plan =
            BurstPlan(
                listOf(
                    Bubble(0, "plan-a", Duration.ZERO),
                    Bubble(1, "plan-b", Duration.ZERO),
                    Bubble(2, "plan-c", Duration.ZERO),
                ),
            )
        val outcome = service(executor, scheduler, reeval, audit).execute(ShadowMode.LIVE, action, plan)

        assertThat(outcome).isInstanceOf(ExecutionOutcome.PartiallyCancelled::class.java)
        val partial = outcome as ExecutionOutcome.PartiallyCancelled
        assertThat(executor.sentBubbleIndexes).containsExactly(0) // 첫 버블만 전송.
        assertThat(partial.messageIds).hasSize(1)
        assertThat(partial.reason).isEqualTo(ActionExecutionService.REASON_CONTEXT_CHANGED)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
        assertThat(audit.phasesOf(action.identity.value))
            .contains(ActionAuditPhase.SENT, ActionAuditPhase.PARTIALLY_CANCELLED)
    }

    @Test
    fun `T021 — 429 retryAfter 가 예산 안이면 1회만 재시도하고 성공한다`() {
        val executor =
            RecordingDiscordExecutor(
                sendResults =
                    ArrayDeque(
                        listOf(
                            ExecutionResult.Failed(ActionFailureReason.DISCORD_TRANSIENT, retryAfter = Duration.ofSeconds(2)),
                            ExecutionResult.Sent("msg-retry"),
                        ),
                    ),
            )
        val scheduler = InMemoryActionScheduler(clock)
        val action = typingSpeakAction()
        scheduler.put(action)

        val outcome =
            service(executor, scheduler, ControllableReevaluation(), InMemoryActionAudit())
                .execute(ShadowMode.LIVE, action, BurstPlan.single("plan-a"))

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Completed::class.java)
        assertThat(executor.sentMessageIds).containsExactly("msg-retry")
    }

    @Test
    fun `T021 — staleness 가 상한을 넘으면 뒤늦게 쏟아내지 않고 취소한다`() {
        // staleness 상한을 0 에 가깝게(아주 작게) 두면 두 번째 버블 전에 시계가 전진해 drop 된다.
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val action = typingSpeakAction()
        scheduler.put(action)
        val plan = BurstPlan(listOf(Bubble(0, "a", Duration.ZERO), Bubble(1, "b", Duration.ZERO)))
        val gate = BackpressureGate(BackpressurePolicy(maxStaleness = Duration.ofMillis(50)))

        // 시계를 advance 하는 executor 래핑: 첫 전송 후 시간을 크게 전진시켜 두 번째 버블이 too-stale 로 drop.
        val advancing =
            object : RecordingDiscordExecutor() {
                override fun sendBubble(
                    channelId: String,
                    speechPlanRef: String,
                    bubbleIndex: Int,
                    replyToMessageId: String?,
                ): ExecutionResult {
                    val r = super.sendBubble(channelId, speechPlanRef, bubbleIndex, replyToMessageId)
                    clock.advance(Duration.ofSeconds(5)) // 다음 버블 사전 검사에서 staleness 초과.
                    return r
                }
            }

        val outcome = service(advancing, scheduler, ControllableReevaluation(), audit, gate).execute(ShadowMode.LIVE, action, plan)

        assertThat(outcome).isInstanceOf(ExecutionOutcome.PartiallyCancelled::class.java)
        assertThat(advancing.sentBubbleIndexes).containsExactly(0) // 첫 버블만, 나머지 미전송.
        assertThat((outcome as ExecutionOutcome.PartiallyCancelled).reason).isEqualTo(ActionExecutionService.REASON_TOO_STALE)
    }
}
