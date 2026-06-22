package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.domain.service.critic.CriticReason
import com.discordassistant.central.speech.domain.service.critic.MemoryConsistencyCritic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T019: 유효 기억과 모순되는 후보를 폐기한다(사실을 새로 발명해 고치지 않는다). */
class MemoryConsistencyCriticTest {
    private val critic = MemoryConsistencyCritic()

    @Test
    fun `rejects candidate that contradicts a current-valid memory (polarity flip)`() {
        val packet =
            SpeechCriticFixtures.packet(
                memoryRefs = listOf(SpeechCriticFixtures.memory("민수는 고양이를 키운다")),
            )
        // 기억: 키운다(긍정) ↔ 후보: 안 키운다(부정) — 같은 주제, 어긋난 극성.
        val candidate = SpeechCriticFixtures.candidate("c1", "민수는 고양이를 안 키우잖아")
        val verdict = critic.evaluate(candidate, packet)
        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.MEMORY_CONTRADICTION)
    }

    @Test
    fun `accepts candidate consistent with memory (same polarity)`() {
        val packet =
            SpeechCriticFixtures.packet(
                memoryRefs = listOf(SpeechCriticFixtures.memory("민수는 고양이를 키운다")),
            )
        val candidate = SpeechCriticFixtures.candidate("c1", "민수 고양이 진짜 귀엽더라")
        assertThat(critic.evaluate(candidate, packet).accepted).isTrue()
    }

    @Test
    fun `accepts candidate unrelated to memory (no shared topic, conservative)`() {
        val packet =
            SpeechCriticFixtures.packet(
                memoryRefs = listOf(SpeechCriticFixtures.memory("민수는 고양이를 키운다")),
            )
        val candidate = SpeechCriticFixtures.candidate("c1", "오늘 점심 뭐 먹지")
        assertThat(critic.evaluate(candidate, packet).accepted).isTrue()
    }

    @Test
    fun `no memory refs means nothing to contradict`() {
        val packet = SpeechCriticFixtures.packet()
        assertThat(critic.evaluate(SpeechCriticFixtures.candidate("c1", "아무말"), packet).accepted).isTrue()
    }
}
