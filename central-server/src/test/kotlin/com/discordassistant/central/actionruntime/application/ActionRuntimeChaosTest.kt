package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.execution.ActionExecutionService
import com.discordassistant.central.actionruntime.application.execution.BackpressureGate
import com.discordassistant.central.actionruntime.application.recovery.RecoveryDisposition
import com.discordassistant.central.actionruntime.application.recovery.RestartRecoveryService
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.BurstPlan
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
import java.time.Duration
import java.time.Instant

/**
 * scheduler crash·restart chaos 테스트(NEXA-P13-T024, experiment).
 *
 * claim 직후·GLM(본문 생성) 직후·첫 버블 직후의 프로세스 중단을 모의하고, 재시작 복구([RestartRecoveryService],
 * T010) 후 다시 처리한다. **acceptance(T024): 중복 전송 0, 유실된 terminal audit 0.**
 *
 * 모델링: 크래시 = 실행 흐름을 중간에 끊고 lease 를 만료시킨 채 둔다. 재시작 = 만료 lease 회수 후 in-flight 상태에
 * 따라 안전 정리(REEVALUATING/TYPING → 재예약 후 재실행; PARTIALLY_SENT → 재전송 없이 종결). 전송 executor 는
 * **누적** 호출을 세므로(재시작을 거쳐도 리셋되지 않음) 같은 버블이 두 번 전송됐는지 정확히 검출된다.
 */
class ActionRuntimeChaosTest {
    private val due = Instant.parse("2026-01-01T00:00:00Z")

    private fun speak(contextVersion: Long = 1L) =
        ScheduledSocialAction.create(
            decisionId = "dec-chaos",
            sampledActionIndex = 0,
            type = ScheduledActionType.SPEAK,
            target = ActionTarget("guild-1", "chan-1", "thread-1"),
            executeAfter = due,
            contextVersion = contextVersion,
        )

    @Test
    fun `claim 직후 크래시 — 재시작 후 정확히 1회 전송, 중복 0`() {
        val clock = MutableTestClock(due)
        val scheduler = InMemoryActionScheduler(clock)
        val executor = RecordingDiscordExecutor()
        val audit = InMemoryActionAudit()
        val action = speak()
        scheduler.schedule(action)

        // claim(REEVALUATING) + lease — 그리고 즉시 크래시(아무 전송도 안 함). lease 를 곧 만료.
        val claimedAt = clock.instant()
        scheduler.claimDue(now = claimedAt, leaseExpiresAt = claimedAt.plus(Duration.ofSeconds(5)), limit = 10)
        // 크래시 후 lease 만료까지 시간 경과.
        clock.advance(Duration.ofSeconds(10))

        // 재시작 복구: REEVALUATING → 재예약(SCHEDULED). 본문 미전송이라 이중 전송 위험 0.
        val recovery = RestartRecoveryService(scheduler, clock).recoverOnStartup()
        assertThat(recovery.single().disposition).isEqualTo(RecoveryDisposition.RESCHEDULED)

        // 다시 due 처리 + 실행 — 정확히 1회 전송.
        val reclaimed = scheduler.claimDue(clock.instant(), clock.instant().plus(Duration.ofSeconds(5)), 10).single()
        val execution = ActionExecutionService(executor, scheduler, ControllableReevaluation(), audit, BackpressureGate(), clock)
        execution.execute(ShadowMode.LIVE, reclaimed.action, BurstPlan.single("plan-a"))

        assertThat(executor.sentBubbleIndexes).containsExactly(0) // 중복 전송 0.
        assertThat(scheduler.find(action.identity)!!.status).isEqualTo(ActionStatus.COMPLETED) // terminal 도달.
        assertThat(audit.phasesOf(action.identity.value)).contains(ActionAuditPhase.COMPLETED) // terminal audit 유실 0.
    }

    @Test
    fun `GLM 본문 생성 직후 크래시(TYPING) — 재시작 후 정확히 1회 전송, 중복 0`() {
        val clock = MutableTestClock(due)
        val scheduler = InMemoryActionScheduler(clock)
        val executor = RecordingDiscordExecutor()
        val audit = InMemoryActionAudit()
        // 본문 생성 후 TYPING 상태로 in-flight, lease 만료된 채 크래시.
        val typing =
            speak()
                .markScheduled()
                .beginReevaluation()
                .passReevaluation() // TYPING
        scheduler.put(typing, leaseExpiresAt = due.plus(Duration.ofSeconds(5)))
        clock.advance(Duration.ofSeconds(10)) // lease 만료.

        // 재시작: TYPING(본문 미전송) → 재예약. 이중 전송 위험 0.
        val recovery = RestartRecoveryService(scheduler, clock).recoverOnStartup()
        assertThat(recovery.single().disposition).isEqualTo(RecoveryDisposition.RESCHEDULED)

        val reclaimed = scheduler.claimDue(clock.instant(), clock.instant().plus(Duration.ofSeconds(5)), 10).single()
        val execution = ActionExecutionService(executor, scheduler, ControllableReevaluation(), audit, BackpressureGate(), clock)
        execution.execute(ShadowMode.LIVE, reclaimed.action, BurstPlan.single("plan-a"))

        assertThat(executor.sentBubbleIndexes).containsExactly(0) // 정확히 1회.
        assertThat(scheduler.find(typing.identity)!!.status).isEqualTo(ActionStatus.COMPLETED)
        assertThat(audit.phasesOf(typing.identity.value)).contains(ActionAuditPhase.COMPLETED)
    }

    @Test
    fun `첫 버블 직후 크래시(PARTIALLY_SENT) — 재시작 시 재전송하지 않음, 중복 0`() {
        val clock = MutableTestClock(due)
        val scheduler = InMemoryActionScheduler(clock)
        val executor = RecordingDiscordExecutor()
        // 첫 버블을 이미 1회 보낸 뒤 크래시한 상태를 모델링: executor 에 1건 전송 기록을 심고,
        // 행동은 PARTIALLY_SENT in-flight 로 lease 만료.
        executor.sendBubble("chan-1", "a", 0, null) // 크래시 전 보낸 첫 버블(누적 카운트에 1 반영).
        assertThat(executor.sentBubbleIndexes).containsExactly(0)
        val partial =
            speak()
                .markScheduled()
                .beginReevaluation()
                .passReevaluation()
                .markPartiallySent() // PARTIALLY_SENT
        scheduler.put(partial, leaseExpiresAt = due.plus(Duration.ofSeconds(5)))
        clock.advance(Duration.ofSeconds(10)) // lease 만료.

        // 재시작 복구: PARTIALLY_SENT → 재전송 없이 종결(COMPLETED_NO_RESEND, T010 핵심).
        val recovery = RestartRecoveryService(scheduler, clock).recoverOnStartup()
        assertThat(recovery.single().disposition).isEqualTo(RecoveryDisposition.COMPLETED_NO_RESEND)

        // 핵심: 재시작이 같은(또는 남은) 버블을 다시 보내지 않는다 — 전송 누적은 여전히 1.
        assertThat(executor.sentBubbleIndexes).containsExactly(0) // 중복 전송 0.
        assertThat(scheduler.find(partial.identity)!!.status).isEqualTo(ActionStatus.COMPLETED) // terminal 도달(유실 0).
    }

    @Test
    fun `복구는 두 번 돌려도 멱등 — 중복 전송·중복 종결 0`() {
        val clock = MutableTestClock(due)
        val scheduler = InMemoryActionScheduler(clock)
        val executor = RecordingDiscordExecutor()
        val typing = speak().markScheduled().beginReevaluation().passReevaluation()
        scheduler.put(typing, leaseExpiresAt = due.plus(Duration.ofSeconds(5)))
        clock.advance(Duration.ofSeconds(10))

        val recovery = RestartRecoveryService(scheduler, clock)
        recovery.recoverOnStartup()
        // 두 번째 복구 호출은 회수할 만료 lease 가 없어 no-op(재예약 중복 없음).
        val second = recovery.recoverOnStartup()
        assertThat(second).isEmpty()
        assertThat(executor.totalExecutorCalls).isZero() // 복구 자체는 전송하지 않는다.
    }
}
