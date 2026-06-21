package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.service.critic.CriticReason
import com.discordassistant.central.speech.domain.service.critic.RepetitionDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T018: 최근 니아 버스트와 n-gram 유사도로 반복·자기복제 후보를 감점(탈락)한다. */
class RepetitionDetectorTest {
    private val detector = RepetitionDetector()

    @Test
    fun `rejects candidate nearly identical to a recent nia utterance`() {
        val packet =
            SpeechCriticFixtures.packet(
                turns =
                    listOf(
                        ConversationTurn("user_1", "오늘 날씨 어때"),
                        ConversationTurn("nia", "헐 그거 완전 인정이지 ㅋㅋㅋ"),
                    ),
            )
        val candidate = SpeechCriticFixtures.candidate("c1", "헐 그거 완전 인정이지 ㅋㅋㅋ")
        val verdict = detector.evaluate(candidate, packet)
        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.REPETITION)
    }

    @Test
    fun `accepts a fresh, distinct candidate`() {
        val packet =
            SpeechCriticFixtures.packet(
                turns =
                    listOf(
                        ConversationTurn("nia", "헐 그거 완전 인정이지 ㅋㅋㅋ"),
                    ),
            )
        val candidate = SpeechCriticFixtures.candidate("c1", "음 나는 좀 다르게 생각해 보면 또 괜찮을 듯")
        assertThat(detector.evaluate(candidate, packet).accepted).isTrue()
    }

    @Test
    fun `first utterance (no prior nia turns) cannot be a repeat`() {
        val packet =
            SpeechCriticFixtures.packet(turns = listOf(ConversationTurn("user_1", "안녕")))
        assertThat(detector.evaluate(SpeechCriticFixtures.candidate("c1", "안녕 반가워"), packet).accepted).isTrue()
    }
}
