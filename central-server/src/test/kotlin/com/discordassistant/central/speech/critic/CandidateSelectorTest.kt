package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.application.generation.CandidateSelector
import com.discordassistant.central.speech.application.generation.SelectionResult
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.service.critic.AssistantStyleDetector
import com.discordassistant.central.speech.domain.service.critic.MemoryConsistencyCritic
import com.discordassistant.central.speech.domain.service.critic.RepetitionDetector
import com.discordassistant.central.speech.domain.service.critic.TargetAndSceneCritic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T021: 비평 통과 후보 중 softmax+seed 로 확률 선택. 통과 0이면 침묵. 결정론 재현. */
class CandidateSelectorTest {
    private val critics =
        listOf(
            AssistantStyleDetector(),
            RepetitionDetector(),
            MemoryConsistencyCritic(),
            TargetAndSceneCritic(),
        )
    private val selector = CandidateSelector(critics)
    private val packet = SpeechCriticFixtures.packet()

    private fun cand(
        id: String,
        text: String,
        uncertainty: Double = 0.0,
    ) = SpeechCandidate(candidateId = id, bubbles = listOf(text), uncertainty = uncertainty)

    @Test
    fun `all candidates rejected yields Silence (fallback to silence)`() {
        val all =
            listOf(
                cand("c1", "제가 도와드릴까요?"),
                cand("c2", "좋은 질문이에요!"),
            )
        assertThat(selector.select(all, packet, seed = 1L)).isEqualTo(SelectionResult.Silence)
    }

    @Test
    fun `selection is deterministic for the same seed`() {
        val candidates =
            listOf(
                cand("c1", "오 그거 좋다", uncertainty = 0.1),
                cand("c2", "음 나는 좀 다른데", uncertainty = 0.2),
                cand("c3", "헐 진짜?", uncertainty = 0.15),
            )
        val first = selector.select(candidates, packet, seed = 42L) as SelectionResult.Selected
        val second = selector.select(candidates, packet, seed = 42L) as SelectionResult.Selected
        assertThat(first.candidate.candidateId).isEqualTo(second.candidate.candidateId)
    }

    @Test
    fun `different seeds can pick different candidates (style not frozen to argmax)`() {
        val candidates =
            listOf(
                cand("c1", "오 그거 좋다", uncertainty = 0.1),
                cand("c2", "음 나는 좀 다른데", uncertainty = 0.12),
                cand("c3", "헐 진짜?", uncertainty = 0.11),
            )
        // 높은 온도로 다양성을 키운 selector — 여러 seed 에서 둘 이상의 후보가 선택돼야 한다.
        val diverse = CandidateSelector(critics, temperature = 1.0)
        val picked =
            (0L until 50L)
                .map { (diverse.select(candidates, packet, seed = it) as SelectionResult.Selected).candidate.candidateId }
                .toSet()
        assertThat(picked.size).isGreaterThan(1)
    }

    @Test
    fun `only surviving candidates are eligible (rejected ones never chosen)`() {
        val candidates =
            listOf(
                cand("bad", "언제든 말씀해 주세요"), // assistant-style → 탈락
                cand("good", "오 그거 좋다"),
            )
        val picked =
            (0L until 30L)
                .map { (selector.select(candidates, packet, seed = it) as SelectionResult.Selected).candidate.candidateId }
                .toSet()
        assertThat(picked).containsExactly("good")
    }
}
