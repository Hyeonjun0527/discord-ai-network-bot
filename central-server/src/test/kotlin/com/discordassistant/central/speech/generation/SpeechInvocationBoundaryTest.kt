package com.discordassistant.central.speech.generation

import com.discordassistant.central.speech.application.generation.CandidateGenerationService
import com.discordassistant.central.speech.application.generation.GateResult
import com.discordassistant.central.speech.application.generation.ReasoningModeSelector
import com.discordassistant.central.speech.application.generation.SkipReason
import com.discordassistant.central.speech.application.generation.SpeechGenerationGate
import com.discordassistant.central.speech.application.generation.SpeechTrigger
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.application.privacy.ExternalPayloadMinimizer
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.SocialActPromptCompiler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P14-T023: IGNORE/WAIT/REACT, stale SPEAK, consent revoke 에서 generation 포트 호출 **0회**.
 *
 * acceptance: quota/requestlog 에도 generation 요청이 생기지 않는다 — 포트가 한 번도 불리지 않으므로 그 아래
 * routing 호출·사용량 기록 자체가 발생하지 않는다(비용·안전 핵심). 호출 횟수를 세는 fake 포트로 입증한다.
 */
class SpeechInvocationBoundaryTest {
    /** 호출 횟수를 세는 fake — generation 이 한 번이라도 불리면 calls 가 증가한다(quota/requestlog 트리거 대용). */
    private class CountingGenerationPort : SpeechGenerationPort {
        var calls = 0

        override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult {
            calls++
            return SpeechGenerationResult(listOf(SpeechCandidate("c1", listOf("안녕!"))))
        }
    }

    private fun gate(port: SpeechGenerationPort): SpeechGenerationGate {
        val service =
            CandidateGenerationService(
                generationPort = port,
                socialActCompiler = SocialActPromptCompiler(),
                burstCompiler = BurstPromptCompiler(),
                reasoningModeSelector = ReasoningModeSelector(),
                payloadMinimizer = ExternalPayloadMinimizer(),
            )
        return SpeechGenerationGate(service)
    }

    private val packet = SpeechGenerationFixtures.packet()

    @Test
    fun `IGNORE never calls the generation port`() {
        val port = CountingGenerationPort()
        val result = gate(port).generateIfSpeaking(SpeechTrigger.IGNORE, packet)
        assertThat(port.calls).isZero()
        assertThat(result.invokedGeneration).isFalse()
        assertThat(result.skipReason).isEqualTo(SkipReason.NOT_SPEAK)
        assertThat(result.result.isEmpty).isTrue()
    }

    @Test
    fun `WAIT and REACT never call the generation port`() {
        for (trigger in listOf(SpeechTrigger.WAIT, SpeechTrigger.REACT, SpeechTrigger.OTHER)) {
            val port = CountingGenerationPort()
            val result = gate(port).generateIfSpeaking(trigger, packet)
            assertThat(port.calls).withFailMessage("trigger %s must not call generation", trigger).isZero()
            assertThat(result.invokedGeneration).isFalse()
        }
    }

    @Test
    fun `stale SPEAK never calls the generation port`() {
        val port = CountingGenerationPort()
        val result = gate(port).generateIfSpeaking(SpeechTrigger.SPEAK, packet, stale = true)
        assertThat(port.calls).isZero()
        assertThat(result.skipReason).isEqualTo(SkipReason.STALE)
    }

    @Test
    fun `consent revoke never calls the generation port`() {
        val port = CountingGenerationPort()
        val result = gate(port).generateIfSpeaking(SpeechTrigger.SPEAK, packet, consentRevoked = true)
        assertThat(port.calls).isZero()
        assertThat(result.skipReason).isEqualTo(SkipReason.CONSENT_REVOKED)
    }

    @Test
    fun `valid SPEAK invokes the generation port exactly once`() {
        val port = CountingGenerationPort()
        val result: GateResult = gate(port).generateIfSpeaking(SpeechTrigger.SPEAK, packet)
        assertThat(port.calls).isEqualTo(1)
        assertThat(result.invokedGeneration).isTrue()
        assertThat(result.result.isEmpty).isFalse()
    }

    @Test
    fun `participation action kind maps to speech trigger correctly`() {
        assertThat(SpeechTrigger.fromActionKind("speak")).isEqualTo(SpeechTrigger.SPEAK)
        assertThat(SpeechTrigger.fromActionKind("ignore")).isEqualTo(SpeechTrigger.IGNORE)
        assertThat(SpeechTrigger.fromActionKind("wait")).isEqualTo(SpeechTrigger.WAIT)
        assertThat(SpeechTrigger.fromActionKind("react")).isEqualTo(SpeechTrigger.REACT)
        assertThat(SpeechTrigger.fromActionKind("cancel")).isEqualTo(SpeechTrigger.OTHER)
    }
}
