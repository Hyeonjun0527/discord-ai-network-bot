package com.discordassistant.central.speech.generation

import com.discordassistant.central.speech.application.generation.CandidateGenerationService
import com.discordassistant.central.speech.application.generation.GenerationBudget
import com.discordassistant.central.speech.application.generation.ReasoningModeSelector
import com.discordassistant.central.speech.application.port.out.ReasoningMode
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.application.privacy.ExternalPayloadMinimizer
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.SocialActPromptCompiler
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T011: 후보 다중 생성 — 후보 수가 비용 cap을 넘지 않고 설정 가능. */
class CandidateGenerationServiceTest {
    private class CapturingPort : SpeechGenerationPort {
        var lastRequest: SpeechGenerationRequest? = null

        override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult {
            lastRequest = request
            val candidates = (1..request.candidateCount).map { SpeechCandidate("c$it", listOf("버블 $it")) }
            return SpeechGenerationResult(candidates, modelMetadata = "fake-model")
        }
    }

    private fun service(port: SpeechGenerationPort) =
        CandidateGenerationService(
            generationPort = port,
            socialActCompiler = SocialActPromptCompiler(),
            burstCompiler = BurstPromptCompiler(),
            reasoningModeSelector = ReasoningModeSelector(),
            payloadMinimizer = ExternalPayloadMinimizer(),
        )

    @Test
    fun `candidate count is clamped to budget cap (configurable, not exceeded)`() {
        val port = CapturingPort()
        val budget = GenerationBudget(maxCandidates = 3, maxOutputTokens = 256, maxContextTokens = 512)
        val result = service(port).generate(SpeechGenerationFixtures.packet(), budget)
        assertThat(result.candidates).hasSize(3)
        assertThat(port.lastRequest!!.candidateCount).isEqualTo(3)
        assertThat(result.modelMetadata).isEqualTo("fake-model")
    }

    @Test
    fun `wider budget still capped by contract max`() {
        val port = CapturingPort()
        val budget = GenerationBudget(maxCandidates = 99, maxOutputTokens = 256, maxContextTokens = 512)
        service(port).generate(SpeechGenerationFixtures.packet(), budget)
        assertThat(port.lastRequest!!.candidateCount).isEqualTo(SpeechGenerationRequest.MAX_CANDIDATES)
    }

    @Test
    fun `assembled system prompt carries identity prohibitions and burst constraint`() {
        val port = CapturingPort()
        service(port).generate(SpeechGenerationFixtures.packet(), GenerationBudget.DEFAULT)
        val req = port.lastRequest!!
        assertThat(req.systemPrompt).contains("니아")
        assertThat(req.systemPrompt).contains("하지 않을 것")
        assertThat(req.systemPrompt).contains("정확히 1개")
        // user prompt는 최소화된 장면.
        assertThat(req.userPrompt).contains("focus_thread")
    }

    @Test
    fun `reasoning mode comes from policy not model`() {
        val port = CapturingPort()
        service(port).generate(
            SpeechGenerationFixtures.packet(socialAct = SpeechSocialAct.CORRECT),
            GenerationBudget.DEFAULT,
        )
        assertThat(port.lastRequest!!.reasoningMode).isEqualTo(ReasoningMode.THINKING)
    }
}
