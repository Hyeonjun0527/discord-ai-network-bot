package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.SpeechTarget
import com.discordassistant.central.speech.domain.service.critic.CriticReason
import com.discordassistant.central.speech.domain.service.critic.TargetAndSceneCritic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T020: 대상·장면에 안 맞는 후보(cross-thread 끌어오기)를 탈락시킨다. */
class TargetAndSceneCriticTest {
    private val critic = TargetAndSceneCritic()

    @Test
    fun `rejects cross-thread reference (foreign pseudonym key)`() {
        val packet =
            SpeechCriticFixtures.packet(
                focusThreadKey = "thread_1",
                target = SpeechTarget.member("user_1"),
                turns = listOf(ConversationTurn("user_1", "안녕")),
            )
        // 후보가 현재 장면에 없는 thread_9 / user_42 를 끌어온다 — cross-thread reference.
        val candidate =
            SpeechCriticFixtures.candidate("c1", "아까 thread_9 에서 user_42 가 한 말이 더 맞던데")
        val verdict = critic.evaluate(candidate, packet)
        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.TARGET_OR_SCENE_MISMATCH)
    }

    @Test
    fun `accepts candidate referencing only in-scene keys`() {
        val packet =
            SpeechCriticFixtures.packet(
                focusThreadKey = "thread_1",
                target = SpeechTarget.member("user_1"),
                turns = listOf(ConversationTurn("user_1", "안녕")),
            )
        val candidate = SpeechCriticFixtures.candidate("c1", "user_1 너 방금 그거 진짜야?")
        assertThat(critic.evaluate(candidate, packet).accepted).isTrue()
    }

    @Test
    fun `accepts candidate with no pseudonym references`() {
        val packet = SpeechCriticFixtures.packet()
        assertThat(critic.evaluate(SpeechCriticFixtures.candidate("c1", "오 그거 좋다"), packet).accepted).isTrue()
    }
}
