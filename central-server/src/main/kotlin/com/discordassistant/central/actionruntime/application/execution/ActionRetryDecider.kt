package com.discordassistant.central.actionruntime.application.execution

import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import java.time.Duration
import java.time.Instant

/**
 * 실패 분류 후 **재시도/영구 실패** 결정 application 서비스(NEXA-P13-T009).
 *
 * 실행이 실패하면 그 원인([ActionFailureReason])과 현재 [ScheduledSocialAction] 의 attempt/maxAttempts 로 **다음에
 * 무엇을 할지** 결정한다. retryable(transient)이고 시도 여유가 있으면 backoff 후 재예약, 아니면(영구 실패거나 소진)
 * 영구 [com.discordassistant.central.actionruntime.domain.model.ActionStatus.FAILED] 로 종결한다.
 *
 * **acceptance(T009) — 영구 실패를 무한 재시도하지 않고 상태와 reason 이 남는다**: 도메인
 * [ScheduledSocialAction.retryTransient] 가 maxAttempts 경계를 강제하므로 무한 재시도 경로가 없고, FAIL 결정에는
 * 항상 [ActionFailureReason] 이 실린다.
 *
 * 시간은 [Clock] 주입(테스트 시간 제어 — Date.now 직접 금지, T008 과 일관)으로 backoff due 시각을 계산한다.
 *
 * 순수성 경계: application 레이어 — 도메인 타입·표준 타입만. Spring/JPA/JDA 미참조.
 */
class ActionRetryDecider(
    private val clock: java.time.Clock,
    /** 첫 재시도 backoff(이후 attempt 에 따라 지수 증가). */
    private val baseBackoff: Duration = DEFAULT_BASE_BACKOFF,
) {
    /**
     * [action] 이 [reason] 으로 실패했을 때의 결정을 돌려준다.
     * - retryable & 시도 여유: [RetryDecision.Retry] (backoff 후 [Retry.nextExecuteAfter] 로 재예약, attempt+1).
     * - 영구 실패 또는 시도 소진: [RetryDecision.Fail] ([reason] 보존).
     */
    fun decide(
        action: ScheduledSocialAction,
        reason: ActionFailureReason,
    ): RetryDecision {
        val nextAttempt = action.attempt + 1
        return if (reason.isRetryable && nextAttempt < action.maxAttempts) {
            val backoff = baseBackoff.multipliedBy(1L shl action.attempt) // 지수 backoff(attempt=0 → base).
            RetryDecision.Retry(nextExecuteAfter = Instant.now(clock).plus(backoff), attempt = nextAttempt)
        } else {
            RetryDecision.Fail(reason)
        }
    }

    companion object {
        /** 기본 첫 backoff(지수의 base). */
        val DEFAULT_BASE_BACKOFF: Duration = Duration.ofSeconds(5)
    }
}

/**
 * 실패 후 다음 행동 결정(application 값 객체). scheduler/실행 경로가 이 결과로 reschedule 또는 fail 을 호출한다.
 */
sealed interface RetryDecision {
    /** bounded 재시도 — [nextExecuteAfter] 에 [attempt] 회차로 재예약(SCHEDULED 복귀). */
    data class Retry(
        val nextExecuteAfter: Instant,
        val attempt: Int,
    ) : RetryDecision

    /** 영구 실패 종결 — [reason] 을 남기고 FAILED. 무한 재시도 없음(T009). */
    data class Fail(
        val reason: ActionFailureReason,
    ) : RetryDecision
}
