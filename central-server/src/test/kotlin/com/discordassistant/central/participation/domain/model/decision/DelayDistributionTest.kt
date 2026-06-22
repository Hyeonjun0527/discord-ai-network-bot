package com.discordassistant.central.participation.domain.model.decision

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/** NEXA-P08-T004 DelayDistribution 의 acceptance 단위 테스트. */
class DelayDistributionTest {
    @Test
    fun `acceptance — 초기 계약 구간이 모두 존재한다 (즉시·3~10·10~30·30~120·never)`() {
        assertThat(DelayBucket.entries).containsExactly(
            DelayBucket.IMMEDIATE,
            DelayBucket.SHORT,
            DelayBucket.MEDIUM,
            DelayBucket.LONG,
            DelayBucket.NEVER,
        )
        assertThat(DelayBucket.SHORT.lowerBound).isEqualTo(Duration.ofSeconds(3))
        assertThat(DelayBucket.SHORT.upperBound).isEqualTo(Duration.ofSeconds(10))
        assertThat(DelayBucket.MEDIUM.lowerBound).isEqualTo(Duration.ofSeconds(10))
        assertThat(DelayBucket.LONG.upperBound).isEqualTo(Duration.ofSeconds(120))
        assertThat(DelayBucket.NEVER.lowerBound).isNull()
    }

    @Test
    fun `acceptance — NEVER 는 발사하지 않는다 (IGNORE 와 다른 축)`() {
        // NEVER 구간이 뽑히면 fires=false 인 ActionDelay (행동은 골랐으나 이번 창엔 발사 안 함).
        val sampled = DelayDistribution.NEVER.sample(seed = 42)
        assertThat(sampled.fires).isFalse()
        assertThat(sampled).isEqualTo(ActionDelay.NEVER)
        // 대조: IMMEDIATE 는 발사한다.
        assertThat(DelayDistribution.IMMEDIATE.sample(seed = 42).fires).isTrue()
    }

    @Test
    fun `확률 합이 1이 아니면 거부한다`() {
        assertThatThrownBy {
            DelayDistribution(mapOf(DelayBucket.IMMEDIATE to 0.5, DelayBucket.SHORT to 0.2))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `sample 은 결정론적이다 (같은 seed = 같은 결과)`() {
        val dist =
            DelayDistribution(
                mapOf(
                    DelayBucket.IMMEDIATE to 0.25,
                    DelayBucket.SHORT to 0.25,
                    DelayBucket.MEDIUM to 0.25,
                    DelayBucket.LONG to 0.25,
                ),
            )
        assertThat(dist.sample(7L)).isEqualTo(dist.sample(7L))
    }

    @Test
    fun `SHORT 구간 샘플은 3~10초 범위 안이다`() {
        val dist = DelayDistribution(mapOf(DelayBucket.SHORT to 1.0))
        repeat(20) { i ->
            val d = dist.sample(i.toLong())
            assertThat(d.fires).isTrue()
            assertThat(d.duration).isBetween(Duration.ofSeconds(3), Duration.ofSeconds(10))
        }
    }

    @Test
    fun `ActionDelay fire 는 음수를 거부한다`() {
        assertThatThrownBy { ActionDelay.fire(Duration.ofSeconds(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
