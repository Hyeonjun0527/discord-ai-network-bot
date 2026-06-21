package com.discordassistant.central.participation.domain.model.decision

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.reflect.full.memberProperties

/** NEXA-P08-T005 BurstProfile 의 acceptance 단위 테스트. */
class BurstProfileTest {
    @Test
    fun `acceptance — 실제 문구를 생성하지 않고 형태만 정한다 (텍스트 필드 부재)`() {
        val props = BurstProfile::class.memberProperties.map { it.name }
        assertThat(props).contains(
            "fragmentCountWeights",
            "maxFragmentLength",
            "gapLowerBound",
            "gapUpperBound",
            "reactionOnlyProbability",
        )
        // 형태만: 어떤 텍스트/원문 필드도 없다.
        assertThat(props).doesNotContain("text", "content", "sentence", "fragments", "message")

        val shapeProps = SampledBurstShape::class.memberProperties.map { it.name }
        assertThat(shapeProps).doesNotContain("text", "content", "sentence")
    }

    @Test
    fun `메시지 수·길이·간격·reaction-only 가능성을 표현한다`() {
        val profile =
            BurstProfile(
                fragmentCountWeights = mapOf(1 to 0.7, 2 to 0.3),
                maxFragmentLength = 200,
                gapLowerBound = Duration.ofSeconds(1),
                gapUpperBound = Duration.ofSeconds(4),
                reactionOnlyProbability = 0.1,
            )
        assertThat(profile.mostLikelyFragmentCount).isEqualTo(1)
        assertThat(profile.maxFragmentLength).isEqualTo(200)
        assertThat(profile.reactionOnlyProbability).isEqualTo(0.1)
    }

    @Test
    fun `조각 수 확률 합이 1이 아니면 거부한다`() {
        assertThatThrownBy {
            BurstProfile(
                fragmentCountWeights = mapOf(1 to 0.5),
                maxFragmentLength = 100,
                gapLowerBound = Duration.ZERO,
                gapUpperBound = Duration.ZERO,
                reactionOnlyProbability = 0.0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `gapLowerBound 가 upperBound 보다 크면 거부한다`() {
        assertThatThrownBy {
            BurstProfile(
                fragmentCountWeights = mapOf(1 to 1.0),
                maxFragmentLength = 100,
                gapLowerBound = Duration.ofSeconds(5),
                gapUpperBound = Duration.ofSeconds(2),
                reactionOnlyProbability = 0.0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `sample 은 결정론적이며 형태만 만든다`() {
        val profile =
            BurstProfile(
                fragmentCountWeights = mapOf(1 to 0.5, 3 to 0.5),
                maxFragmentLength = 280,
                gapLowerBound = Duration.ofSeconds(1),
                gapUpperBound = Duration.ofSeconds(3),
                reactionOnlyProbability = 0.0,
            )
        val a = profile.sample(11L)
        val b = profile.sample(11L)
        assertThat(a).isEqualTo(b)
        assertThat(a.fragmentCount).isIn(1, 3)
        assertThat(a.gapBetweenFragments).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(3))
        assertThat(a.reactionOnly).isFalse()
    }

    @Test
    fun `singleLine 은 한 조각 reaction-only 없음 기본형이다`() {
        val profile = BurstProfile.singleLine()
        assertThat(profile.mostLikelyFragmentCount).isEqualTo(1)
        assertThat(profile.reactionOnlyProbability).isEqualTo(0.0)
    }
}
