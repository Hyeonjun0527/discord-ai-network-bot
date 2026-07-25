package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.application.generation.CandidateCriticFilter
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.service.critic.AssistantStyleDetector
import com.discordassistant.central.speech.domain.service.critic.MemoryConsistencyCritic
import com.discordassistant.central.speech.domain.service.critic.RepetitionDetector
import com.discordassistant.central.speech.domain.service.critic.TargetAndSceneCritic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 로컬 critic이 거부한 후보를 최종 선택 전에 제거한다. */
class CandidateCriticFilterTest {
    private val filter =
        CandidateCriticFilter(
            listOf(
                AssistantStyleDetector(),
                RepetitionDetector(),
                MemoryConsistencyCritic(),
                TargetAndSceneCritic(),
            ),
        )
    private val packet = SpeechCriticFixtures.packet()

    @Test
    fun `모든 후보가 거부되면 생존 후보가 없다`() {
        val candidates =
            listOf(
                candidate("c1", "제가 도와드릴까요?"),
                candidate("c2", "좋은 질문이에요!"),
            )

        assertThat(filter.survivors(candidates, packet)).isEmpty()
    }

    @Test
    fun `생존 후보의 생성 순서를 보존한다`() {
        val candidates =
            listOf(
                candidate("c1", "오 그거 좋다"),
                candidate("c2", "음 나는 좀 다른데"),
                candidate("c3", "헐 진짜?"),
            )

        assertThat(filter.survivors(candidates, packet).map(SpeechCandidate::candidateId))
            .containsExactly("c1", "c2", "c3")
    }

    @Test
    fun `거부된 후보는 생존 목록에서 제외한다`() {
        val candidates =
            listOf(
                candidate("bad", "언제든 말씀해 주세요"),
                candidate("good", "오 그거 좋다"),
            )

        assertThat(filter.survivors(candidates, packet).map(SpeechCandidate::candidateId))
            .containsExactly("good")
    }

    private fun candidate(
        id: String,
        text: String,
    ) = SpeechCandidate(candidateId = id, bubbles = listOf(text))
}
