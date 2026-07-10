package com.discordassistant.central.actionruntime.domain

import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.IllegalActionTransition
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P13-T001/T002/T004/T009 — ScheduledSocialAction aggregate·상태 머신·idempotency·bounded retry 단위 테스트.
 */
class ScheduledSocialActionTest {
    private val target = ActionTarget(guildPseudonym = "g1", channelId = "c1", threadId = "t1")

    private fun newAction(maxAttempts: Int = 3) =
        ScheduledSocialAction.create(
            decisionId = "decision-1",
            sampledActionIndex = 0,
            type = ScheduledActionType.SPEAK,
            target = target,
            executeAfter = Instant.parse("2026-01-01T00:00:00Z"),
            contextVersion = 5,
            originRolloutMode = ShadowMode.LIVE,
            maxAttempts = maxAttempts,
        )

    // ── T001: 상태 전이는 도메인 메서드로만, 불법 전이 거부 ──

    @Test
    fun `초기 상태는 CONSIDERING 이고 happy path 전이가 새 인스턴스를 만든다`() {
        val a = newAction()
        assertThat(a.status).isEqualTo(ActionStatus.CONSIDERING)
        assertThat(a.originRolloutMode).isEqualTo(ShadowMode.LIVE)

        val scheduled = a.markScheduled()
        assertThat(scheduled.status).isEqualTo(ActionStatus.SCHEDULED)
        assertThat(a.status).isEqualTo(ActionStatus.CONSIDERING) // 원본 불변

        val typing = scheduled.beginReevaluation().passReevaluation()
        assertThat(typing.status).isEqualTo(ActionStatus.TYPING)
        assertThat(typing.complete().status).isEqualTo(ActionStatus.COMPLETED)
    }

    @Test
    fun `불법 전이는 IllegalActionTransition 으로 거부된다`() {
        val a = newAction()
        // CONSIDERING → TYPING 은 그래프에 없다.
        assertThatThrownBy { a.passReevaluation() }.isInstanceOf(IllegalActionTransition::class.java)
        // CONSIDERING → COMPLETED 도 불법.
        assertThatThrownBy { a.complete() }.isInstanceOf(IllegalActionTransition::class.java)
    }

    @Test
    fun `terminal 상태에서는 어떤 전이도 불가능하다`() {
        val completed =
            newAction()
                .markScheduled()
                .beginReevaluation()
                .passReevaluation()
                .complete()
        assertThat(completed.status.isTerminal).isTrue()
        assertThatThrownBy { completed.cancel() }.isInstanceOf(IllegalActionTransition::class.java)
        assertThatThrownBy { completed.fail(ActionFailureReason.DISCORD_TRANSIENT) }
            .isInstanceOf(IllegalActionTransition::class.java)
    }

    @Test
    fun `예약 SPEAK 는 어느 비-terminal 상태에서든 취소 가능하다`() {
        val scheduled = newAction().markScheduled()
        assertThat(scheduled.cancel().status).isEqualTo(ActionStatus.CANCELLED)

        val reeval = scheduled.beginReevaluation()
        assertThat(reeval.cancel().status).isEqualTo(ActionStatus.CANCELLED)
    }

    // ── T002: terminal·재시도 상태 ──

    @Test
    fun `terminal 상태 집합이 정확하다`() {
        assertThat(ActionStatus.entries.filter { it.isTerminal })
            .containsExactlyInAnyOrder(ActionStatus.COMPLETED, ActionStatus.CANCELLED, ActionStatus.FAILED)
    }

    // ── T004: idempotency key ──

    @Test
    fun `같은 decision·index 면 같은 ActionIdentity 다(안정적 key)`() {
        val a = ActionIdentity.of("decision-1", 0)
        val b = ActionIdentity.of("decision-1", 0)
        assertThat(a).isEqualTo(b)
        assertThat(a.value).isEqualTo("decision-1#0")
    }

    @Test
    fun `같은 decision 의 다른 index 는 다른 key 다(멀티 버블 구분)`() {
        assertThat(ActionIdentity.of("decision-1", 0)).isNotEqualTo(ActionIdentity.of("decision-1", 1))
    }

    // ── T009: bounded retry ──

    @Test
    fun `transient 실패는 maxAttempts 까지만 재시도하고 그 후 FAILED 로 수렴한다`() {
        var a = newAction(maxAttempts = 2).markScheduled().beginReevaluation().passReevaluation()
        // attempt 0 → retry → SCHEDULED, attempt 1
        a = a.retryTransient(ActionFailureReason.DISCORD_TRANSIENT)
        assertThat(a.status).isEqualTo(ActionStatus.SCHEDULED)
        assertThat(a.attempt).isEqualTo(1)
        // attempt 1 → next=2 == maxAttempts → 더는 재시도 안 함 → FAILED
        a = a.beginReevaluation().passReevaluation().retryTransient(ActionFailureReason.DISCORD_TRANSIENT)
        assertThat(a.status).isEqualTo(ActionStatus.FAILED)
        assertThat(a.failureReason).isEqualTo(ActionFailureReason.DISCORD_TRANSIENT)
    }

    @Test
    fun `영구 실패 원인은 즉시 FAILED 로 종결한다(재시도 안 함)`() {
        val a = newAction().markScheduled().beginReevaluation().passReevaluation()
        val failed = a.retryTransient(ActionFailureReason.PERMISSION_DENIED)
        assertThat(failed.status).isEqualTo(ActionStatus.FAILED)
        assertThat(failed.failureReason).isEqualTo(ActionFailureReason.PERMISSION_DENIED)
        assertThat(failed.attempt).isEqualTo(0) // 재시도 회차 증가 없음
    }

    // ── due / stale 보조 메서드 ──

    @Test
    fun `isDue 는 executeAfter 도래 후 true 다`() {
        val a = newAction()
        assertThat(a.isDue(Instant.parse("2025-12-31T23:59:59Z"))).isFalse()
        assertThat(a.isDue(Instant.parse("2026-01-01T00:00:00Z"))).isTrue()
    }

    @Test
    fun `isStale 는 contextVersion 이 다를 때 true 다`() {
        val a = newAction() // contextVersion=5
        assertThat(a.isStale(5)).isFalse()
        assertThat(a.isStale(6)).isTrue()
    }
}
