package com.discordassistant.central.conversation.application.dispatch

import java.time.Duration

/**
 * projection worker 재시도 정책(NEXA-P03-T017). 일시 오류(transient)와 영구 오류(permanent)를 분류하고,
 * **bounded** retry 와 exponential backoff 를 적용한다 — 재시도가 무한 루프로 가지 않게 상한([maxAttempts])을
 * 둔다.
 *
 * **acceptance(T017)**: 순수 함수(시계 부작용 없음)라 테스트가 결정론적이다. [decide] 는 (시도 횟수, 실패
 * 종류)만으로 다음 행동([RetryDecision])을 계산한다 — 호출자가 [RetryDecision.RetryAfter.delay] 만큼 기다린
 * 뒤 재시도하거나, [RetryDecision.DeadLetter] 면 격리한다. 영구 오류는 backoff 없이 즉시 dead-letter 라
 * 무한 재시도가 원천 차단된다. retry 횟수·간격이 정확하다(아래 backoff 식).
 *
 * backoff: `delay = base * 2^(attempt-1)`, [maxDelay] 로 cap. attempt 는 1-base(첫 시도 실패 후 attempt=1).
 *
 * 순수성: application.dispatch 소속이며 표준 [Duration] 만 본다(Spring/JPA/JDA·시계 미참조 — 호출자가 대기).
 */
class ProjectionRetryPolicy(
    private val maxAttempts: Int,
    private val baseDelay: Duration,
    private val maxDelay: Duration,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts 는 1 이상이어야 한다: $maxAttempts" }
        require(!baseDelay.isNegative && !baseDelay.isZero) { "baseDelay 는 양수여야 한다: $baseDelay" }
        require(maxDelay >= baseDelay) { "maxDelay 는 baseDelay 이상이어야 한다: max=$maxDelay base=$baseDelay" }
    }

    /**
     * [attempt] 번째 시도가 [failure] 로 실패했을 때의 다음 행동.
     *
     * - [FailureKind.PERMANENT]: 재시도 무의미 → 즉시 [RetryDecision.DeadLetter](backoff 없음).
     * - [FailureKind.TRANSIENT] 이고 [attempt] < [maxAttempts]: [RetryDecision.RetryAfter](backoff 지연).
     * - [FailureKind.TRANSIENT] 이지만 [attempt] >= [maxAttempts]: 재시도 소진 → [RetryDecision.DeadLetter].
     *
     * [attempt] 는 1-base(첫 실패가 1). bounded 라 [attempt] 가 [maxAttempts] 에 닿으면 반드시 종료한다.
     */
    fun decide(
        attempt: Int,
        failure: FailureKind,
    ): RetryDecision {
        require(attempt >= 1) { "attempt 는 1 이상이어야 한다: $attempt" }
        if (failure == FailureKind.PERMANENT) {
            return RetryDecision.DeadLetter(reason = "permanent")
        }
        if (attempt >= maxAttempts) {
            return RetryDecision.DeadLetter(reason = "retries-exhausted")
        }
        return RetryDecision.RetryAfter(backoffFor(attempt))
    }

    /** [attempt](1-base) 의 backoff 지연 = `baseDelay * 2^(attempt-1)`, [maxDelay] cap. */
    fun backoffFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt 는 1 이상이어야 한다: $attempt" }
        val baseMs = baseDelay.toMillis()
        val maxMs = maxDelay.toMillis()
        // millis 도메인에서 2^(attempt-1) 배수를 단계별로 곱하되, maxMs 를 넘는 즉시 멈춘다(오버플로 회피).
        var scaledMs = baseMs
        repeat(attempt - 1) {
            if (scaledMs >= maxMs) return@repeat
            scaledMs *= 2
        }
        return Duration.ofMillis(scaledMs.coerceAtMost(maxMs))
    }
}

/** projection 실패 종류 — 재시도 가능 여부를 가른다(호출자가 예외를 분류해 넘긴다). */
enum class FailureKind {
    /** 일시 오류(네트워크·락 경합·일시 부하) — bounded 재시도로 회복 가능. */
    TRANSIENT,

    /** 영구 오류(스키마 불일치·잘못된 데이터·복구 불가) — 재시도 무의미, 즉시 격리. */
    PERMANENT,
}

/** [ProjectionRetryPolicy.decide] 결과 — 재시도(지연 포함) 또는 dead-letter 격리. */
sealed interface RetryDecision {
    /** [delay] 만큼 기다린 뒤 재시도하라(호출자가 대기). */
    data class RetryAfter(
        val delay: Duration,
    ) : RetryDecision

    /** 더는 재시도하지 말고 [reason] 코드로 dead-letter 격리하라(무한 루프 차단). */
    data class DeadLetter(
        val reason: String,
    ) : RetryDecision
}
