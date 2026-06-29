package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.service.critic.BurstShapeCritic
import com.discordassistant.central.speech.domain.service.critic.CriticReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BurstShapeCriticTest {
    private val critic = BurstShapeCritic()

    @Test
    fun `rejects candidates that ignore exact bubble count`() {
        val packet = SpeechCriticFixtures.packet(burstShape = SpeechBurstShape(2, 80, false))
        val verdict = critic.evaluate(SpeechCriticFixtures.candidate("c1", "한 문장만 보냄"), packet)

        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.BURST_SHAPE_MISMATCH)
    }

    @Test
    fun `rejects candidates that exceed per bubble length`() {
        val packet = SpeechCriticFixtures.packet(burstShape = SpeechBurstShape(1, 10, false))
        val verdict = critic.evaluate(SpeechCriticFixtures.candidate("c1", "이건 열 글자를 훨씬 넘는다"), packet)

        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.BURST_SHAPE_MISMATCH)
    }

    @Test
    fun `reaction only shape rejects text candidates`() {
        val packet = SpeechCriticFixtures.packet(burstShape = SpeechBurstShape(1, 80, true))
        val verdict = critic.evaluate(SpeechCriticFixtures.candidate("c1", "말로 답하기"), packet)

        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.BURST_SHAPE_MISMATCH)
    }

    @Test
    fun `accepts candidate matching selected burst shape`() {
        val packet = SpeechCriticFixtures.packet(burstShape = SpeechBurstShape(2, 80, false))
        val verdict = critic.evaluate(SpeechCriticFixtures.candidate("c1", "아 미안", "지금 봤어"), packet)

        assertThat(verdict.accepted).isTrue()
    }
}
