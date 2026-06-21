package com.discordassistant.central.speech.generation

import com.discordassistant.central.speech.application.generation.CandidateSelector
import com.discordassistant.central.speech.application.generation.ShadowSample
import com.discordassistant.central.speech.application.generation.SpeechShadowEvaluator
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.model.MemoryRef
import com.discordassistant.central.speech.domain.service.critic.AssistantStyleDetector
import com.discordassistant.central.speech.domain.service.critic.MemoryConsistencyCritic
import com.discordassistant.central.speech.domain.service.critic.RepetitionDetector
import com.discordassistant.central.speech.domain.service.critic.TargetAndSceneCritic
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** NEXA-P14-T024: 전송 없이 후보 품질·비용을 shadow 평가(assistant-style rate·contradiction·latency·cost + human). */
class SpeechShadowEvaluatorTest {
    private val selector =
        CandidateSelector(
            listOf(
                AssistantStyleDetector(),
                RepetitionDetector(),
                MemoryConsistencyCritic(),
                TargetAndSceneCritic(),
            ),
        )
    private val evaluator = SpeechShadowEvaluator(selector)

    private fun cand(
        id: String,
        text: String,
    ) = SpeechCandidate(candidateId = id, bubbles = listOf(text))

    @Test
    fun `aggregates assistant-style and contradiction rates plus latency and cost without sending`() {
        val packet =
            SpeechGenerationFixtures.packet(
                memoryRefs = listOf(MemoryRef("민수는 고양이를 키운다", "stated", 0.9)),
            )
        val samples =
            listOf(
                ShadowSample(
                    packet = packet,
                    candidates =
                        listOf(
                            cand("c1", "언제든 말씀해 주세요"), // assistant-style
                            cand("c2", "민수는 고양이를 안 키우잖아"), // contradiction
                            cand("c3", "오 그거 좋다"), // 통과
                            cand("c4", "음 나는 좀 다른데"), // 통과
                        ),
                    latencyMillis = 800,
                    costTokens = 120,
                    humanScore = 4.0,
                ),
            )

        val report = evaluator.evaluate(samples)

        assertThat(report.sampleCount).isEqualTo(1)
        assertThat(report.candidateCount).isEqualTo(4)
        assertThat(report.assistantStyleRate).isCloseTo(0.25, within(1e-9))
        assertThat(report.contradictionRate).isCloseTo(0.25, within(1e-9))
        assertThat(report.rejectionRate).isCloseTo(0.5, within(1e-9))
        assertThat(report.avgLatencyMillis).isCloseTo(800.0, within(1e-9))
        assertThat(report.avgCostTokens).isCloseTo(120.0, within(1e-9))
        assertThat(report.avgHumanScore).isEqualTo(4.0)
    }

    @Test
    fun `empty samples produce an empty report`() {
        assertThat(evaluator.evaluate(emptyList())).isEqualTo(com.discordassistant.central.speech.application.generation.ShadowReport.EMPTY)
    }

    @Test
    fun `human score is optional (auto metrics only)`() {
        val packet = SpeechGenerationFixtures.packet()
        val report =
            evaluator.evaluate(
                listOf(ShadowSample(packet, listOf(cand("c1", "오 그거 좋다")), latencyMillis = 100, costTokens = 30)),
            )
        assertThat(report.avgHumanScore).isNull()
        assertThat(report.assistantStyleRate).isZero()
    }
}
