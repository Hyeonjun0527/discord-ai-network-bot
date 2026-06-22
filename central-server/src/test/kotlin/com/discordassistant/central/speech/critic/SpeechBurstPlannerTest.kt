package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.application.generation.PlanRejection
import com.discordassistant.central.speech.application.generation.PlanResult
import com.discordassistant.central.speech.application.generation.SpeechBurstPlanner
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T022: 선택 후보를 burstProfile 에 맞춰 버블·간격 계획으로. 빈 버블·길이 초과·과도한 수 거부. */
class SpeechBurstPlannerTest {
    private val planner = SpeechBurstPlanner()

    private fun cand(vararg bubbles: String) = SpeechCandidate(candidateId = "c1", bubbles = bubbles.toList())

    @Test
    fun `plans multi-bubble burst with gaps between bubbles`() {
        val shape = SpeechBurstShape(fragmentCount = 3, maxFragmentLength = 100, reactionOnly = false)
        val result = planner.plan(cand("안녕", "오랜만이야", "잘 지냈어?"), shape)
        assertThat(result).isInstanceOf(PlanResult.Planned::class.java)
        val plan = (result as PlanResult.Planned).burstPlan
        assertThat(plan.bubbleCount).isEqualTo(3)
        assertThat(plan.isMultiBubble).isTrue()
        assertThat(
            plan.bubbles
                .last()
                .gapAfter.isZero,
        ).isTrue() // 마지막 버블은 간격 없음
        assertThat(
            plan.bubbles
                .first()
                .gapAfter.isZero,
        ).isFalse()
    }

    @Test
    fun `rejects empty bubbles`() {
        val shape = SpeechBurstShape(1, 100, false)
        val result = planner.plan(cand("   ", ""), shape)
        assertThat(result).isEqualTo(PlanResult.Rejected(PlanRejection.EMPTY_BUBBLE))
    }

    @Test
    fun `rejects bubble exceeding Discord length limit`() {
        val shape = SpeechBurstShape(1, maxFragmentLength = 5000, reactionOnly = false)
        val tooLong = "가".repeat(SpeechBurstPlanner.DISCORD_MAX_MESSAGE_LENGTH + 1)
        val result = planner.plan(cand(tooLong), shape)
        assertThat(result).isEqualTo(PlanResult.Rejected(PlanRejection.LENGTH_OVERFLOW))
    }

    @Test
    fun `rejects bubble exceeding policy fragment length`() {
        val shape = SpeechBurstShape(1, maxFragmentLength = 10, reactionOnly = false)
        val result = planner.plan(cand("이건 정책 형태 상한 10자를 넘는 긴 문장이야"), shape)
        assertThat(result).isEqualTo(PlanResult.Rejected(PlanRejection.LENGTH_OVERFLOW))
    }

    @Test
    fun `rejects too many bubbles beyond policy fragment count`() {
        val shape = SpeechBurstShape(fragmentCount = 2, maxFragmentLength = 100, reactionOnly = false)
        val result = planner.plan(cand("a", "b", "c"), shape)
        assertThat(result).isEqualTo(PlanResult.Rejected(PlanRejection.TOO_MANY_BUBBLES))
    }

    @Test
    fun `reaction-only shape is rejected (no speech burst)`() {
        val shape = SpeechBurstShape(1, 100, reactionOnly = true)
        val result = planner.plan(cand("뭐라도"), shape)
        assertThat(result).isEqualTo(PlanResult.Rejected(PlanRejection.REACTION_ONLY))
    }
}
