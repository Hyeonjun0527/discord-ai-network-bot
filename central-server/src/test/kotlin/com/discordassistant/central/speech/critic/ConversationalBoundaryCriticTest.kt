package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.domain.service.critic.ConversationalBoundaryCritic
import com.discordassistant.central.speech.domain.service.critic.CriticReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConversationalBoundaryCriticTest {
    private val critic = ConversationalBoundaryCritic()

    @Test
    fun `direct support request rejects long comfort monologue`() {
        val packet =
            SpeechCriticFixtures.packet(
                rawContextSceneData = "user_1: «야 이럴땐 위로하라고 ㅠㅠ»",
                speechIntent = "한 문장으로 짧게 받아준다",
            )
        val candidate =
            SpeechCriticFixtures.candidate(
                "c1",
                "많이 힘들었겠구나. 네가 지금 느끼는 감정은 아주 자연스럽고, 내가 하나씩 정리해줄게. 첫째로 상황을 받아들이고 둘째로 충분히 쉬어야 해.",
            )

        val verdict = critic.evaluate(candidate, packet)

        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.CONVERSATIONAL_BOUNDARY)
    }

    @Test
    fun `rejects lecture style for low stakes chat`() {
        val packet = SpeechCriticFixtures.packet()
        val candidate = SpeechCriticFixtures.candidate("c1", "첫째, 지금 상황을 정리하고 둘째, 네가 해야 할 일을 말해줄게.")

        assertThat(critic.evaluate(candidate, packet).reason).isEqualTo(CriticReason.CONVERSATIONAL_BOUNDARY)
    }

    @Test
    fun `rejects over familiar claims`() {
        val packet = SpeechCriticFixtures.packet()
        val candidate = SpeechCriticFixtures.candidate("c1", "난 항상 네 편이야. 우리 완전 절친이지.")

        assertThat(critic.evaluate(candidate, packet).reason).isEqualTo(CriticReason.CONVERSATIONAL_BOUNDARY)
    }

    @Test
    fun `rejects emotion assertions on behalf of the user`() {
        val packet = SpeechCriticFixtures.packet()
        val candidate = SpeechCriticFixtures.candidate("c1", "너 지금 너무 외로운 거구나.")

        assertThat(critic.evaluate(candidate, packet).reason).isEqualTo(CriticReason.CONVERSATIONAL_BOUNDARY)
    }

    @Test
    fun `accepts short natural acknowledgement`() {
        val packet =
            SpeechCriticFixtures.packet(
                rawContextSceneData = "user_1: «야 이럴땐 위로하라고»",
                speechIntent = "한 문장으로 짧게 받아준다",
            )

        val verdict = critic.evaluate(SpeechCriticFixtures.candidate("c1", "아 미안, 지금 봤어."), packet)

        assertThat(verdict.accepted).isTrue()
    }
}
