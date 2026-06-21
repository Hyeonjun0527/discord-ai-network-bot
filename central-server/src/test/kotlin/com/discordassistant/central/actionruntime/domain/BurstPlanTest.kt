package com.discordassistant.central.actionruntime.domain

import com.discordassistant.central.actionruntime.adapter.outbound.multiresponse.MultiResponseBurstAdapter
import com.discordassistant.central.actionruntime.domain.model.BurstPlan
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 멀티 응답 버스트 매핑 acceptance(NEXA-P13-T019): 기존 pseudo-streaming API 가 정책 action 수를 늘리지 않는다.
 */
class BurstPlanTest {
    private val adapter = MultiResponseBurstAdapter()

    @Test
    fun `T019 — 의사-스트림은 버블 1개로 매핑되어 action 수를 늘리지 않는다`() {
        val plan = adapter.fromPseudoStream("final-plan")
        assertThat(plan.bubbleCount).isEqualTo(1)
        assertThat(plan.isMultiBubble).isFalse()
        assertThat(plan.bubbles.single().speechPlanRef).isEqualTo("final-plan")
    }

    @Test
    fun `정책이 명시한 멀티 버블만 action 수가 버블 수와 같다`() {
        val plan = adapter.fromBubbles(listOf("a", "b", "c"), gap = Duration.ofMillis(500))
        assertThat(plan.bubbleCount).isEqualTo(3)
        assertThat(plan.isMultiBubble).isTrue()
        // 마지막 버블만 gap 0, 나머지는 지정 gap.
        assertThat(plan.bubbles.map { it.gapAfter })
            .containsExactly(Duration.ofMillis(500), Duration.ofMillis(500), Duration.ZERO)
        assertThat(plan.totalSpan).isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun `빈 버블 계획은 거부된다`() {
        assertThatThrownBy { BurstPlan(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { adapter.fromBubbles(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `single 팩토리는 버블 1개`() {
        assertThat(BurstPlan.single("x").bubbleCount).isEqualTo(1)
    }
}
