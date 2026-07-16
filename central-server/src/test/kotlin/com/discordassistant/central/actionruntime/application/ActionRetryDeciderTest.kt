package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.execution.ActionRetryDecider
import com.discordassistant.central.actionruntime.application.execution.RetryDecision
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.support.MutableTestClock
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * NEXA-P13-T009 — ActionRetryDecider: transient 만 bounded 재시도, 영구 실패/소진은 FAILED 로 수렴.
 */
class ActionRetryDeciderTest {
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val decider = ActionRetryDecider(clock, baseBackoff = Duration.ofSeconds(5))

    private fun action(
        attempt: Int,
        maxAttempts: Int = 3,
    ) = ScheduledSocialAction
        .create(
            decisionId = "d1",
            sampledActionIndex = 0,
            type = ScheduledActionType.SPEAK,
            target = ActionTarget("g1", "c1", "t1"),
            executeAfter = clock.instant(),
            contextVersion = 1,
            originRolloutMode = ShadowMode.LIVE,
            maxAttempts = maxAttempts,
        ).copy(attempt = attempt)

    @Test
    fun `transient 실패는 여유가 있으면 지수 backoff 후 재시도다`() {
        val d0 = decider.decide(action(attempt = 0), ActionFailureReason.DISCORD_TRANSIENT)
        assertThat(d0).isInstanceOf(RetryDecision.Retry::class.java)
        d0 as RetryDecision.Retry
        assertThat(d0.attempt).isEqualTo(1)
        assertThat(d0.nextExecuteAfter).isEqualTo(clock.instant().plus(Duration.ofSeconds(5))) // base * 2^0

        val d1 = decider.decide(action(attempt = 1), ActionFailureReason.DISCORD_TRANSIENT) as RetryDecision.Retry
        assertThat(d1.nextExecuteAfter).isEqualTo(clock.instant().plus(Duration.ofSeconds(10))) // base * 2^1
    }

    @Test
    fun `시도 소진 시 영구 실패로 수렴한다(무한 재시도 없음)`() {
        // attempt=2, max=3 → next=3 == max → Fail
        val d = decider.decide(action(attempt = 2, maxAttempts = 3), ActionFailureReason.DISCORD_TRANSIENT)
        assertThat(d).isEqualTo(RetryDecision.Fail(ActionFailureReason.DISCORD_TRANSIENT))
    }

    @Test
    fun `영구 실패 원인은 즉시 Fail 이다`() {
        listOf(
            ActionFailureReason.PERMISSION_DENIED,
            ActionFailureReason.TARGET_MISSING,
            ActionFailureReason.INVALID_REQUEST,
            ActionFailureReason.MODEL_TIMEOUT,
        ).forEach { reason ->
            assertThat(decider.decide(action(attempt = 0), reason)).isEqualTo(RetryDecision.Fail(reason))
        }
    }

    @Test
    fun `실패 분류의 retryable 플래그가 정확하다`() {
        assertThat(ActionFailureReason.DISCORD_TRANSIENT.isRetryable).isTrue()
        assertThat(ActionFailureReason.PERMISSION_DENIED.isRetryable).isFalse()
        assertThat(ActionFailureReason.TARGET_MISSING.isRetryable).isFalse()
        assertThat(ActionFailureReason.INVALID_REQUEST.isRetryable).isFalse()
        assertThat(ActionFailureReason.MODEL_TIMEOUT.isRetryable).isFalse()
    }
}
