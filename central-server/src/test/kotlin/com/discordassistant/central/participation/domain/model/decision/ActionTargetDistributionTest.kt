package com.discordassistant.central.participation.domain.model.decision

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** NEXA-P08-T003 ActionTargetDistribution 의 acceptance 단위 테스트. */
class ActionTargetDistributionTest {
    @Test
    fun `acceptance — 대상 확률 합이 1이 아니면 거부한다`() {
        assertThatThrownBy {
            ActionTargetDistribution(
                candidates = listOf(TargetCandidate(TargetRef(TargetKind.MESSAGE, "m1"), 0.5)),
                noneProbability = 0.2,
                resolverVersion = "v1",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `합이 1이면 유효하다`() {
        val dist =
            ActionTargetDistribution(
                candidates =
                    listOf(
                        TargetCandidate(TargetRef(TargetKind.MESSAGE, "m1"), 0.6),
                        TargetCandidate(TargetRef(TargetKind.MEMBER, "u1"), 0.3),
                    ),
                noneProbability = 0.1,
                resolverVersion = "v1",
            )
        assertThat(dist.mostLikely?.target?.id).isEqualTo("m1")
        assertThat(dist.isLikelyNone).isFalse()
    }

    @Test
    fun `acceptance — 존재하지 않는 ID 검증`() {
        val dist =
            ActionTargetDistribution(
                candidates =
                    listOf(
                        TargetCandidate(TargetRef(TargetKind.THREAD, "t1"), 0.4),
                        TargetCandidate(TargetRef(TargetKind.MESSAGE, "ghost"), 0.4),
                    ),
                noneProbability = 0.2,
                resolverVersion = "v1",
            )
        assertThatThrownBy { dist.requireKnownTargets(setOf("t1")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ghost")
        assertThatCode { dist.requireKnownTargets(setOf("t1", "ghost")) }.doesNotThrowAnyException()
    }

    @Test
    fun `none 은 대상 없음 분포다`() {
        val none = ActionTargetDistribution.none("v1")
        assertThat(none.noneProbability).isEqualTo(1.0)
        assertThat(none.candidates).isEmpty()
        assertThat(none.isLikelyNone).isTrue()
    }

    @Test
    fun `중복 대상 ref 는 거부된다`() {
        assertThatThrownBy {
            ActionTargetDistribution(
                candidates =
                    listOf(
                        TargetCandidate(TargetRef(TargetKind.MEMBER, "u1"), 0.4),
                        TargetCandidate(TargetRef(TargetKind.MEMBER, "u1"), 0.4),
                    ),
                noneProbability = 0.2,
                resolverVersion = "v1",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
