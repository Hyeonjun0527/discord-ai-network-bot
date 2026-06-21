package com.discordassistant.central.actionruntime.domain

import com.discordassistant.central.actionruntime.domain.service.BackpressurePolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * rate-limit·backpressure 정책 acceptance(NEXA-P13-T021): 오래 지연된 사회적 응답을 뒤늦게 쏟아내지 않는다.
 */
class BackpressurePolicyTest {
    private val policy = BackpressurePolicy(maxStaleness = Duration.ofSeconds(60))

    @Test
    fun `staleness 상한 안이면 전송 유지`() {
        assertThat(policy.isTooStale(Duration.ofSeconds(59))).isFalse()
        assertThat(policy.isTooStale(Duration.ofSeconds(60))).isFalse()
    }

    @Test
    fun `상한을 넘으면 too-stale(전송 대신 취소)`() {
        assertThat(policy.isTooStale(Duration.ofSeconds(61))).isTrue()
    }

    @Test
    fun `429 backoff 가 예산 안이면 존중·재시도 허용`() {
        assertThat(policy.acceptableBackoff(Duration.ofSeconds(10), Duration.ofSeconds(5))).isTrue()
    }

    @Test
    fun `429 backoff 가 예산을 넘으면 무한 재시도·spam 대신 거절`() {
        assertThat(policy.acceptableBackoff(Duration.ofSeconds(58), Duration.ofSeconds(5))).isFalse()
        // 음수 retryAfter 도 거절(방어).
        assertThat(policy.acceptableBackoff(Duration.ZERO, Duration.ofSeconds(-1))).isFalse()
    }

    @Test
    fun `maxStaleness 는 양수여야 한다`() {
        assertThatThrownBy { BackpressurePolicy(maxStaleness = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
