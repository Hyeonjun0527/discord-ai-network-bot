package com.discordassistant.central.conversation.application.dispatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * NEXA-P03-T017 acceptance: retry 횟수와 간격이 정확하며 무한 루프가 없다(Clock 기반 결정론적 — 순수 함수).
 */
class ProjectionRetryPolicyTest {
    private val policy =
        ProjectionRetryPolicy(
            maxAttempts = 4,
            baseDelay = Duration.ofMillis(100),
            maxDelay = Duration.ofSeconds(1),
        )

    @Test
    fun `영구 오류는 backoff 없이 즉시 dead-letter 다`() {
        val decision = policy.decide(attempt = 1, failure = FailureKind.PERMANENT)
        assertEquals(RetryDecision.DeadLetter("permanent"), decision)
    }

    @Test
    fun `일시 오류는 maxAttempts 전까지 backoff 재시도한다`() {
        // attempt 1,2,3 → RetryAfter, attempt 4(=max) → DeadLetter(소진).
        assertEquals(RetryDecision.RetryAfter(Duration.ofMillis(100)), policy.decide(1, FailureKind.TRANSIENT))
        assertEquals(RetryDecision.RetryAfter(Duration.ofMillis(200)), policy.decide(2, FailureKind.TRANSIENT))
        assertEquals(RetryDecision.RetryAfter(Duration.ofMillis(400)), policy.decide(3, FailureKind.TRANSIENT))
        assertEquals(RetryDecision.DeadLetter("retries-exhausted"), policy.decide(4, FailureKind.TRANSIENT))
    }

    @Test
    fun `backoff 는 maxDelay 로 cap 된다`() {
        // base=100ms, 2^k 증가하다 1s 에서 cap.
        assertEquals(Duration.ofMillis(100), policy.backoffFor(1))
        assertEquals(Duration.ofMillis(200), policy.backoffFor(2))
        assertEquals(Duration.ofMillis(400), policy.backoffFor(3))
        assertEquals(Duration.ofMillis(800), policy.backoffFor(4))
        assertEquals(Duration.ofSeconds(1), policy.backoffFor(5), "1600ms 는 maxDelay 1s 로 cap")
        assertEquals(Duration.ofSeconds(1), policy.backoffFor(50), "큰 지수도 cap, 오버플로 없음")
    }

    @Test
    fun `재시도는 bounded 라 무한 루프가 없다`() {
        // 어떤 attempt 든 maxAttempts 이상이면 반드시 DeadLetter(종료).
        for (attempt in 4..1000) {
            val decision = policy.decide(attempt, FailureKind.TRANSIENT)
            assertTrue(decision is RetryDecision.DeadLetter, "attempt=$attempt 는 종료해야 한다")
        }
    }

    @Test
    fun `maxAttempts 1 이면 첫 일시 실패도 즉시 dead-letter 다`() {
        val once = ProjectionRetryPolicy(1, Duration.ofMillis(10), Duration.ofMillis(10))
        assertEquals(RetryDecision.DeadLetter("retries-exhausted"), once.decide(1, FailureKind.TRANSIENT))
    }
}
