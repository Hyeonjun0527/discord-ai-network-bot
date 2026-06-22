package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.domain.service.critic.AssistantStyleDetector
import com.discordassistant.central.speech.domain.service.critic.CriticReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T017: AI 도우미 말투 후보를 거른다(니아는 멤버, 도우미 아님). 사용자 문장은 건드리지 않는다. */
class AssistantStyleDetectorTest {
    private val detector = AssistantStyleDetector()
    private val packet = SpeechCriticFixtures.packet()

    @Test
    fun `rejects helper-assistant phrasing`() {
        val candidate = SpeechCriticFixtures.candidate("c1", "제가 도와드릴까요? 언제든 말씀해 주세요!")
        val verdict = detector.evaluate(candidate, packet)
        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.ASSISTANT_STYLE)
    }

    @Test
    fun `rejects good-question and summary patterns`() {
        assertThat(detector.evaluate(SpeechCriticFixtures.candidate("c1", "좋은 질문이에요!"), packet).rejected).isTrue()
        assertThat(detector.evaluate(SpeechCriticFixtures.candidate("c2", "정리하자면, 세 가지가 있어요"), packet).rejected)
            .isTrue()
        assertThat(detector.evaluate(SpeechCriticFixtures.candidate("c3", "다음과 같은 단계를 따르세요"), packet).rejected)
            .isTrue()
    }

    @Test
    fun `rejects english assistant phrasing`() {
        assertThat(detector.evaluate(SpeechCriticFixtures.candidate("c1", "How can I help you today?"), packet).rejected)
            .isTrue()
        assertThat(
            detector.evaluate(SpeechCriticFixtures.candidate("c2", "Let me know if you need anything"), packet).rejected,
        ).isTrue()
    }

    @Test
    fun `accepts natural member-like chatter`() {
        assertThat(detector.evaluate(SpeechCriticFixtures.candidate("c1", "헐 진짜? 나도 그거 봤는데 ㅋㅋ"), packet).accepted)
            .isTrue()
        assertThat(detector.evaluate(SpeechCriticFixtures.candidate("c2", "음 그건 좀 애매한데"), packet).accepted).isTrue()
    }

    @Test
    fun `blank candidate is accepted (fallback handles emptiness)`() {
        assertThat(detector.evaluate(SpeechCriticFixtures.candidate("c1", "  ", ""), packet).accepted).isTrue()
    }
}
