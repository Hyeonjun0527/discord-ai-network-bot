package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.adapter.outbound.multiresponse.MultiResponseBurstAdapter
import com.discordassistant.central.actionruntime.application.execution.ActionExecutionService
import com.discordassistant.central.actionruntime.application.execution.BackpressureGate
import com.discordassistant.central.actionruntime.application.execution.ExecutionOutcome
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.support.ControllableReevaluation
import com.discordassistant.central.actionruntime.support.InMemoryActionAudit
import com.discordassistant.central.actionruntime.support.InMemoryActionScheduler
import com.discordassistant.central.actionruntime.support.MutableTestClock
import com.discordassistant.central.actionruntime.support.RecordingDiscordExecutor
import com.discordassistant.central.actionruntime.support.typingSpeakAction
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T016 — multiresponse 전송 통합 acceptance.
 *
 * [MultiResponseBurstAdapter] 가 만든 [com.discordassistant.central.actionruntime.domain.model.BurstPlan] 을
 * 실제 실행 경로([ActionExecutionService])로 보내, **기존 다중응답 UX 와 충돌 없이** 전송되는지 검증한다.
 *
 * acceptance 두 축:
 *  1. **충돌 없음**: 기존 pseudo-streaming 결과는 [MultiResponseBurstAdapter.fromPseudoStream] 으로 버블 1개에
 *     매핑돼 전송 action 이 1개다(정책 action 수를 늘리지 않음 — 기존 의사-스트림 UX 보존).
 *  2. **중간 cancel·reply target 보존**: 정책이 명시한 멀티 버블에서 reply 대상이 첫 버블에 보존되고, 도중
 *     contextVersion 이 바뀌면 잔여 버블을 보내지 않고 부분 취소한다(이미 보낸 버블 보존).
 */
class MultiResponseBurstIntegrationTest {
    private val clock = MutableTestClock()
    private val adapter = MultiResponseBurstAdapter()

    private fun service(
        executor: RecordingDiscordExecutor,
        scheduler: InMemoryActionScheduler,
        reeval: ControllableReevaluation,
        audit: InMemoryActionAudit,
    ) = ActionExecutionService(executor, scheduler, reeval, audit, BackpressureGate(), clock)

    @Test
    fun `acceptance — pseudo-stream 매핑은 전송 action 1개로 기존 다중응답 UX 와 충돌하지 않는다`() {
        val executor = RecordingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val action = typingSpeakAction(contextVersion = 1L)
        scheduler.put(action)

        // 기존 의사-스트리밍(제자리 편집) 결과 → 버블 1개.
        val plan = adapter.fromPseudoStream("final-body-ref")
        assertThat(plan.bubbleCount).isEqualTo(1)

        val outcome =
            service(executor, scheduler, ControllableReevaluation(1L), InMemoryActionAudit())
                .execute(ShadowMode.LIVE, action, plan)

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Completed::class.java)
        assertThat(executor.sentBubbleIndexes).containsExactly(0) // 전송 action 1개(다중응답 action 수 불변).
    }

    @Test
    fun `acceptance — reply target 이 첫 버블에 보존되고 도중 cancel 시 잔여 버블을 보내지 않는다`() {
        val reeval = ControllableReevaluation(currentVersion = 1L, validOnReevaluate = false)
        // replyToMessageId 를 캡처하는 executor(첫 버블만 reply 대상이 실려야 한다).
        val replyTargets = mutableMapOf<Int, String?>()
        val executor =
            object : RecordingDiscordExecutor() {
                override fun sendBubble(
                    channelId: String,
                    speechPlanRef: String,
                    bubbleIndex: Int,
                    replyToMessageId: String?,
                ): ExecutionResult {
                    replyTargets[bubbleIndex] = replyToMessageId
                    val result = super.sendBubble(channelId, speechPlanRef, bubbleIndex, replyToMessageId)
                    if (bubbleIndex == 0) reeval.currentVersion = 2L
                    return result
                }
            }
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        val replyToMessageId = "1234567890123456789"
        val action =
            typingSpeakAction(
                contextVersion = 1L,
                threadId = "conversation-focus",
                replyToMessageId = replyToMessageId,
            )
        assertThat(action.target.threadId).isEqualTo("conversation-focus")
        assertThat(action.target.replyToMessageId).isEqualTo(replyToMessageId)
        scheduler.put(action)

        // 정책이 명시한 멀티 버블(3개).
        val plan = adapter.fromBubbles(listOf("b0", "b1", "b2"))
        assertThat(plan.bubbleCount).isEqualTo(3)

        val outcome = service(executor, scheduler, reeval, audit).execute(ShadowMode.LIVE, action, plan)

        // 중간 cancel: 첫 버블만 전송되고 나머지는 미전송, 부분 취소로 종결.
        assertThat(outcome).isInstanceOf(ExecutionOutcome.PartiallyCancelled::class.java)
        assertThat(executor.sentBubbleIndexes).containsExactly(0)
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
        // reply target 보존: 첫 버블에 원 메시지 참조가 실린다(이후 버블은 reply 없음 — 보내지지도 않았다).
        assertThat(replyTargets[0]).isEqualTo(replyToMessageId)
        assertThat(replyTargets).doesNotContainKey(1)
    }
}
