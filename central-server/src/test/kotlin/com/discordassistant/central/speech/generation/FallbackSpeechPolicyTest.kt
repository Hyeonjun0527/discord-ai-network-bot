package com.discordassistant.central.speech.generation

import com.discordassistant.central.speech.application.generation.FallbackSpeechPolicy
import com.discordassistant.central.speech.application.generation.SpeechOutcome
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T016: 안전한 fallback — 잘못된 말 대신 침묵, canned 도우미 템플릿 금지. */
class FallbackSpeechPolicyTest {
    private val policy = FallbackSpeechPolicy()

    @Test
    fun `empty generation result on a substantive act cancels (silence over wrong words)`() {
        val packet = SpeechGenerationFixtures.packet(socialAct = SpeechSocialAct.CORRECT)
        val outcome = policy.decide(SpeechGenerationResult.EMPTY, packet)
        // canned 장문이 아니라 무발화(취소)다.
        assertThat(outcome).isEqualTo(SpeechOutcome.Cancel)
    }

    @Test
    fun `empty result on low-stakes acknowledgement degrades to reaction-only`() {
        val packet =
            SpeechGenerationFixtures.packet(
                socialAct = SpeechSocialAct.ACKNOWLEDGE,
                burstShape = SpeechBurstShape(1, 100, false),
            )
        assertThat(policy.decide(SpeechGenerationResult.EMPTY, packet)).isEqualTo(SpeechOutcome.ReactionOnly)
    }

    @Test
    fun `reaction-only shape always degrades to reaction regardless of candidates`() {
        val packet =
            SpeechGenerationFixtures.packet(
                burstShape = SpeechBurstShape(1, 100, reactionOnly = true),
            )
        val result = SpeechGenerationResult(listOf(SpeechCandidate("c1", listOf("말풍선"))))
        assertThat(policy.decide(result, packet)).isEqualTo(SpeechOutcome.ReactionOnly)
    }

    @Test
    fun `usable candidates produce a Speak outcome`() {
        val packet = SpeechGenerationFixtures.packet()
        val result = SpeechGenerationResult(listOf(SpeechCandidate("c1", listOf("안녕!"))))
        val outcome = policy.decide(result, packet)
        assertThat(outcome).isInstanceOf(SpeechOutcome.Speak::class.java)
        assertThat((outcome as SpeechOutcome.Speak).candidates).hasSize(1)
    }

    @Test
    fun `candidates with only blank bubbles are treated as empty`() {
        val packet = SpeechGenerationFixtures.packet(socialAct = SpeechSocialAct.DISAGREE)
        val result = SpeechGenerationResult(listOf(SpeechCandidate("c1", listOf(" ", ""))))
        assertThat(policy.decide(result, packet)).isEqualTo(SpeechOutcome.Cancel)
    }
}
